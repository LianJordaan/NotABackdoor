package com.lian.notabackdoor.panel.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Console-enabled outbound HTTPS transport. It holds only relay device keys,
 * never Modrinth, SSH, or dashboard-management credentials. The relay operator
 * can observe proxied panel traffic and must be trusted accordingly.
 */
public final class RelayClient implements AutoCloseable {
    private static final int MAX_JOB_JSON = 16 * 1024;
    private static final long MAX_RESPONSE = 21L * 1024 * 1024 * 1024;
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofHours(3);
    private final Path stateFile;
    private final int localPort;
    private final Logger logger;
    private final HttpClient remote;
    private final HttpClient local;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "notabackdoor-relay");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService transfers = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "notabackdoor-relay-transfer");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, FutureTask<Void>> activeTransfers = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile State state;
    private volatile long lastContactNanos;

    public RelayClient(Path dataDirectory, int localPort, Logger logger) throws IOException {
        this(dataDirectory, localPort, logger,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** Package-private HTTP client injection permits a TLS trust-store test. */
    RelayClient(Path dataDirectory, int localPort, Logger logger, HttpClient remote) throws IOException {
        Files.createDirectories(dataDirectory);
        this.stateFile = dataDirectory.resolve("relay.properties");
        this.localPort = localPort;
        this.logger = logger;
        this.remote = remote;
        this.local = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.state = load();
        worker.submit(this::loop);
    }

    public record Pairing(String url, String code, Instant expiresAt) { }

    public static URI origin(String raw) {
        URI value;
        try {
            value = URI.create(raw);
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException("Enter a valid HTTPS relay origin", bad);
        }
        if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null
                || value.getRawUserInfo() != null || value.getRawPath() != null
                && !value.getRawPath().isEmpty() || value.getRawQuery() != null
                || value.getRawFragment() != null || value.getPort() < -1
                || value.toString().endsWith("/")) {
            throw new IllegalArgumentException("Relay address must be one HTTPS origin, such as https://za.example.org");
        }
        return value;
    }

    public synchronized Pairing pair(String rawOrigin) throws IOException, InterruptedException {
        URI endpoint = origin(rawOrigin);
        State current = state;
        if (current != null && current.deviceSecret != null) {
            throw new IllegalStateException("Revoke the existing relay connection before pairing another server");
        }
        if (current != null && current.pendingToken != null
                && current.expiresAt > Instant.now().getEpochSecond()) {
            if (!current.origin.equals(endpoint.toString())) {
                throw new IllegalStateException("A pairing is already pending on another relay");
            }
            return new Pairing(endpoint + "/pair/" + current.id, current.code,
                    Instant.ofEpochSecond(current.expiresAt));
        }
        String pairId = token(18);
        String code = token(24);
        String pendingToken = token(32);
        String deviceSecret = token(32);
        JsonObject body = new JsonObject();
        body.addProperty("pair_id", pairId);
        body.addProperty("code_hash", sha256(code));
        body.addProperty("pending_hash", sha256(pendingToken));
        body.addProperty("device_hash", sha256(deviceSecret));
        HttpResponse<String> answer = remote.send(HttpRequest.newBuilder(
                        endpoint.resolve("/relay/v1/pair/start"))
                .timeout(SHORT_TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        if (answer.statusCode() != 201) {
            throw new IOException("Relay pairing failed (HTTP " + answer.statusCode() + ")");
        }
        JsonObject response = parse(answer.body());
        if (!endpoint.toString().concat("/pair/").concat(pairId).equals(string(response, "url"))) {
            throw new IOException("Relay returned a different pairing link");
        }
        long expiresAt = response.get("expires_at").getAsLong();
        if (expiresAt <= Instant.now().getEpochSecond() || expiresAt > Instant.now().plusSeconds(901).getEpochSecond()) {
            throw new IOException("Relay returned an invalid pairing expiry");
        }
        State pending = new State(endpoint.toString(), pairId, code, pendingToken,
                deviceSecret, expiresAt, false);
        save(pending);
        state = pending;
        lastContactNanos = 0;
        return new Pairing(endpoint + "/pair/" + pairId, code, Instant.ofEpochSecond(expiresAt));
    }

    public synchronized String status() {
        State current = state;
        if (current == null) return "Relay is off. Local access through SSH is still available.";
        if (current.revokePending) return "Relay revocation is queued; no panel requests are being served.";
        if (current.deviceSecret != null && current.pendingToken == null) {
            boolean recent = lastContactNanos > 0 && System.nanoTime() - lastContactNanos
                    < TimeUnit.SECONDS.toNanos(60);
            return recent ? "Relay connected to " + current.origin + "; local SSH access remains available."
                    : "Relay paired with " + current.origin + "; reconnecting. Local SSH access remains available.";
        }
        return "Relay pairing is pending at " + current.origin + "/pair/" + current.id;
    }

    public synchronized String revoke() throws IOException {
        if (state == null) return "Relay is already off.";
        State pending = state.withRevokePending(true);
        save(pending);
        state = pending;
        lastContactNanos = 0;
        activeTransfers.values().forEach(task -> task.cancel(true));
        return "Relay access stopped locally. Remote revocation will retry until acknowledged.";
    }

    private void loop() {
        long delay = 1;
        while (!closed) {
            State current = state;
            if (current == null) {
                pause(2);
                continue;
            }
            try {
                if (current.revokePending) {
                    revokeRemote(current);
                } else if (current.pendingToken != null) {
                    checkPair(current);
                    pause(3);
                } else {
                    poll(current);
                }
                delay = 1;
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                // No secret, URL query, request body or response body is logged.
                logger.log(Level.WARNING, "Relay connection unavailable; retrying in " + delay + " seconds");
                pause(delay);
                delay = Math.min(delay * 2, 30);
            }
        }
    }

    private void checkPair(State current) throws IOException, InterruptedException {
        if (Instant.now().getEpochSecond() >= current.expiresAt) {
            synchronized (this) {
                if (state == current) {
                    state = null;
                    Files.deleteIfExists(stateFile);
                }
            }
            logger.info("Relay pairing expired; run 'nab relay pair <https-origin>' again.");
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("pair_id", current.id);
        body.addProperty("pending_token", current.pendingToken);
        HttpResponse<String> response = remote.send(HttpRequest.newBuilder(
                        URI.create(current.origin + "/relay/v1/pair/status"))
                .timeout(SHORT_TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Pairing status HTTP " + response.statusCode());
        String result = string(parse(response.body()), "status");
        if ("claimed".equals(result)) {
            synchronized (this) {
                if (state == current) {
                    State active = new State(current.origin, current.id, null, null,
                            current.deviceSecret, 0, false);
                    save(active);
                    state = active;
                    lastContactNanos = 0;
                    logger.info("Relay paired. The local panel and SSH fallback remain available.");
                }
            }
        } else if ("expired".equals(result)) {
            synchronized (this) {
                if (state == current) {
                    state = null;
                    Files.deleteIfExists(stateFile);
                }
            }
        } else if (!"pending".equals(result)) {
            throw new IOException("Unexpected relay pairing status");
        }
    }

    private void poll(State current) throws IOException, InterruptedException {
        if (activeTransfers.size() >= 4) {
            pause(1);
            return;
        }
        HttpResponse<String> response = remote.send(HttpRequest.newBuilder(
                        URI.create(current.origin + "/relay/v1/device/poll"))
                .timeout(POLL_TIMEOUT).header("Authorization", bearer(current))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 204) {
            lastContactNanos = System.nanoTime();
            return;
        }
        if (response.statusCode() == 401) {
            logger.warning("Relay credential was rejected; remote access is unavailable.");
            pause(30);
            return;
        }
        if (response.statusCode() != 200 || response.body().length() > MAX_JOB_JSON) {
            throw new IOException("Relay poll failed (HTTP " + response.statusCode() + ")");
        }
        lastContactNanos = System.nanoTime();
        dispatch(current, parse(response.body()));
    }

    private void dispatch(State current, JsonObject job) throws IOException {
        String id = string(job, "id");
        if (!id.matches("[A-Za-z0-9_-]{16,64}")) throw new IOException("Relay sent an invalid request ID");
        FutureTask<Void> transfer = new FutureTask<>(() -> {
            try {
                serve(current, job);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                failJob(current, id);
            } catch (Exception failure) {
                logger.warning("A relayed panel request failed; the browser can retry if safe.");
                failJob(current, id);
            }
            return null;
        }) {
            @Override protected void done() {
                activeTransfers.remove(id, this);
            }
        };
        if (activeTransfers.putIfAbsent(id, transfer) == null) {
            transfers.execute(transfer);
        }
    }

    private void failJob(State current, String id) {
        try {
            remote.send(HttpRequest.newBuilder(URI.create(current.origin
                            + "/relay/v1/device/jobs/" + id + "/fail"))
                    .timeout(SHORT_TIMEOUT).header("Authorization", bearer(current))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException ignored) {
            // The relay or browser may already have closed the job. No request data is logged.
        }
    }

    private void serve(State current, JsonObject job) throws IOException, InterruptedException {
        if (current != state || current.revokePending) return;
        String id = string(job, "id");
        String method = string(job, "method").toUpperCase(Locale.ROOT);
        String path = string(job, "path");
        long size = job.get("body_length").getAsLong();
        if (!id.matches("[A-Za-z0-9_-]{16,64}")
                || !method.matches("GET|HEAD|POST|PUT|DELETE")
                || !path.startsWith("/") || path.startsWith("//") || path.length() > 2048
                || path.indexOf('\\') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0
                || size < -1 || size > 128L * 1024 * 1024) {
            throw new IOException("Relay sent an invalid panel request");
        }
        URI localUri;
        try {
            localUri = URI.create("http://127.0.0.1:" + localPort + path);
        } catch (IllegalArgumentException bad) {
            throw new IOException("Relay sent an invalid panel path", bad);
        }
        URI requestUri = URI.create(current.origin + "/relay/v1/device/jobs/" + id + "/request");
        HttpResponse<InputStream> incoming = remote.send(HttpRequest.newBuilder(requestUri)
                .timeout(TRANSFER_TIMEOUT).header("Authorization", bearer(current))
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (incoming.statusCode() != 200) throw new IOException("Relay request body unavailable");
        try (InputStream source = incoming.body()) {
            HttpRequest.BodyPublisher payload = size == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofInputStream(() -> new LimitedInput(source,
                    size < 0 ? 128L * 1024 * 1024 : size, size >= 0,
                    () -> state == current && !closed && !current.revokePending));
            HttpRequest.Builder localBuilder = HttpRequest.newBuilder(localUri)
                    .timeout(TRANSFER_TIMEOUT).method(method, payload);
            JsonObject headers = job.getAsJsonObject("headers");
            if (headers != null) {
                for (String name : new String[]{"Accept", "Content-Type", "Cookie", "X-CSRF-Token", "X-Confirm-Path"}) {
                    if (headers.has(name)) {
                        String value = headers.get(name).getAsString();
                        if (value.length() > 2048 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                            throw new IOException("Relay sent an invalid panel header");
                        }
                        localBuilder.header(name, value);
                    }
                }
                if (headers.has("Origin") && !method.equals("GET") && !method.equals("HEAD")) {
                    localBuilder.header("Origin", "http://127.0.0.1:" + localPort);
                }
            }
            HttpResponse<InputStream> localResponse = local.send(localBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream localBody = localResponse.body()) {
                HttpRequest.Builder result = HttpRequest.newBuilder(
                                URI.create(current.origin + "/relay/v1/device/jobs/" + id + "/response"))
                        .timeout(TRANSFER_TIMEOUT).header("Authorization", bearer(current))
                        .header("X-Panel-Status", Integer.toString(localResponse.statusCode()))
                        .header("X-Panel-Content-Type", localResponse.headers().firstValue("Content-Type")
                                .orElse("application/octet-stream"));
                localResponse.headers().firstValue("Content-Disposition")
                        .ifPresent(value -> result.header("X-Panel-Disposition", value));
                localResponse.headers().firstValue("Set-Cookie")
                        .ifPresent(value -> result.header("X-Panel-Set-Cookie", value));
                HttpResponse<Void> sent = remote.send(result.POST(HttpRequest.BodyPublishers.ofInputStream(
                                () -> new LimitedInput(localBody, MAX_RESPONSE, false,
                                        () -> state == current && !closed && !current.revokePending))).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (sent.statusCode() != 200) throw new IOException("Relay rejected panel response (HTTP "
                        + sent.statusCode() + ")");
            }
        }
    }

    private void revokeRemote(State current) throws IOException, InterruptedException {
        HttpRequest request;
        if (current.pendingToken != null) {
            JsonObject body = new JsonObject();
            body.addProperty("pair_id", current.id);
            body.addProperty("pending_token", current.pendingToken);
            request = HttpRequest.newBuilder(URI.create(current.origin + "/relay/v1/pair/cancel"))
                    .timeout(SHORT_TIMEOUT).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        } else {
            request = HttpRequest.newBuilder(URI.create(current.origin + "/relay/v1/device/revoke"))
                    .timeout(SHORT_TIMEOUT).header("Authorization", bearer(current))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
        }
        HttpResponse<Void> result = remote.send(request, HttpResponse.BodyHandlers.discarding());
        if (result.statusCode() != 200 && result.statusCode() != 401) {
            throw new IOException("Remote revocation was not acknowledged");
        }
        synchronized (this) {
            if (state == current) {
                state = null;
                Files.deleteIfExists(stateFile);
                logger.info("Relay credential revoked; local SSH access remains available.");
            }
        }
    }

    private String bearer(State current) {
        return "Bearer " + current.id + "." + current.deviceSecret;
    }

    private String token(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JsonObject parse(String value) throws IOException {
        try {
            return JsonParser.parseString(value).getAsJsonObject();
        } catch (RuntimeException bad) {
            throw new IOException("Relay returned invalid JSON", bad);
        }
    }

    private static String string(JsonObject object, String key) throws IOException {
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException bad) {
            throw new IOException("Relay response is missing " + key, bad);
        }
    }

    private State load() throws IOException {
        if (!Files.exists(stateFile)) return null;
        if (Files.isSymbolicLink(stateFile)) throw new IOException("Relay state cannot be a symbolic link");
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(stateFile)) {
            values.load(input);
        }
        if (!"1".equals(values.getProperty("format"))) throw new IOException("Invalid relay state format");
        State loaded;
        try {
            loaded = new State(values.getProperty("origin"), values.getProperty("id"),
                    values.getProperty("code"), values.getProperty("pending_token"),
                    values.getProperty("device_secret"),
                    Long.parseLong(values.getProperty("expires_at", "0")),
                    Boolean.parseBoolean(values.getProperty("revoke_pending", "false")));
            origin(loaded.origin);
            if (loaded.id == null || !loaded.id.matches("[A-Za-z0-9_-]{22,64}")
                    || loaded.deviceSecret == null || loaded.deviceSecret.isBlank()) {
                throw new IllegalArgumentException("Invalid relay state");
            }
        } catch (RuntimeException damaged) {
            throw new IOException("Relay state is damaged; local panel access remains available", damaged);
        }
        return loaded;
    }

    private void save(State value) throws IOException {
        Properties values = new Properties();
        values.setProperty("format", "1");
        values.setProperty("origin", value.origin);
        values.setProperty("id", value.id);
        values.setProperty("device_secret", value.deviceSecret);
        values.setProperty("expires_at", Long.toString(value.expiresAt));
        values.setProperty("revoke_pending", Boolean.toString(value.revokePending));
        if (value.code != null) values.setProperty("code", value.code);
        if (value.pendingToken != null) values.setProperty("pending_token", value.pendingToken);
        Path temporary = Files.createTempFile(stateFile.getParent(), ".relay-", ".tmp");
        try {
            restrict(temporary);
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, "NotABackdoor relay device key; keep private");
            }
            try {
                Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(stateFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs need separate deployment review.
        }
    }

    private void pause(long seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed = true;
        activeTransfers.values().forEach(task -> task.cancel(true));
        transfers.shutdownNow();
        worker.shutdownNow();
    }

    private record State(String origin, String id, String code, String pendingToken,
                         String deviceSecret, long expiresAt, boolean revokePending) {
        State withRevokePending(boolean value) {
            return new State(origin, id, code, pendingToken, deviceSecret, expiresAt, value);
        }
    }

    private static final class LimitedInput extends FilterInputStream {
        private long remaining;
        private final boolean exact;
        private final BooleanSupplier allowed;

        LimitedInput(InputStream source, long maximum, boolean exact, BooleanSupplier allowed) {
            super(source);
            remaining = maximum;
            this.exact = exact;
            this.allowed = allowed;
        }

        @Override
        public int read() throws IOException {
            if (!allowed.getAsBoolean()) throw new IOException("Relay transfer was revoked");
            if (remaining <= 0) {
                if (super.read() == -1) return -1;
                throw new IOException("Relay transfer exceeds the size limit");
            }
            int value = super.read();
            if (value < 0 && exact) throw new IOException("Relay request body ended early");
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (!allowed.getAsBoolean()) throw new IOException("Relay transfer was revoked");
            if (length == 0) return 0;
            if (remaining <= 0) {
                if (super.read() == -1) return -1;
                throw new IOException("Relay transfer exceeds the size limit");
            }
            int count = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (count < 0 && exact) throw new IOException("Relay request body ended early");
            if (count >= 0) remaining -= count;
            return count;
        }
    }
}
