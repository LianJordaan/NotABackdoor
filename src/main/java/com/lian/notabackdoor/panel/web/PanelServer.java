package com.lian.notabackdoor.panel.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.lian.notabackdoor.panel.files.PanelFiles;
import com.lian.notabackdoor.panel.files.PanelBackups;
import com.lian.notabackdoor.panel.files.MinecraftConsole;
import com.lian.notabackdoor.panel.files.SecureFileRoot;
import com.lian.notabackdoor.panel.security.AuthService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** HTTP adapter; authentication and every mutation stay in one request path. */
public final class PanelServer implements AutoCloseable {
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private final JavaPlugin plugin;
    private final AuthService auth;
    private final PanelFiles files;
    private final PanelBackups backups;
    private final Gson gson = new Gson();
    private final HttpServer server;
    private final ExecutorService workers;
    private final PanelLog logs;
    private final MinecraftConsole console;
    private final PanelMetrics metrics;
    private final PanelAccess access;
    private final SetupProbeService setupProbes;

    public PanelServer(JavaPlugin plugin, AuthService auth, PanelFiles files, int port) throws IOException {
        this(plugin, auth, files, PanelAccess.local(port));
    }

    public PanelServer(JavaPlugin plugin, AuthService auth, PanelFiles files, PanelAccess access) throws IOException {
        this(plugin, auth, files, access, new SetupProbeService());
    }

    public PanelServer(JavaPlugin plugin, AuthService auth, PanelFiles files, PanelAccess access,
                       SetupProbeService setupProbes) throws IOException {
        this.plugin = plugin;
        this.auth = auth;
        this.files = files;
        this.access = Objects.requireNonNull(access, "access");
        this.setupProbes = Objects.requireNonNull(setupProbes, "setupProbes");
        PanelBackups preparedBackups = new PanelBackups(files.root(), plugin.getDataFolder().toPath().resolve("backups"));
        HttpServer preparedServer;
        try {
            preparedServer = HttpServer.create(new InetSocketAddress(access.bindAddress(), access.port()), 32);
        } catch (IOException | RuntimeException failure) {
            preparedBackups.close();
            throw failure;
        }
        this.backups = preparedBackups;
        this.server = preparedServer;
        this.workers = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "notabackdoor-panel");
            thread.setDaemon(true);
            return thread;
        });
        this.logs = new PanelLog();
        this.console = new MinecraftConsole(files.root());
        this.metrics = new PanelMetrics(plugin.getDataFolder().toPath());
        server.setExecutor(workers);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
        metrics.start(plugin);
    }

    public SetupProbeService setupProbes() {
        return setupProbes;
    }

    @Override
    public void close() {
        server.stop(1);
        logs.close();
        metrics.close();
        try { console.close(); } catch (IOException error) { plugin.getLogger().warning("Could not close console log handles: " + error.getMessage()); }
        workers.shutdownNow();
        try { backups.close(); } catch (IOException error) { plugin.getLogger().warning("Could not close backup handles: " + error.getMessage()); }
        try { files.close(); } catch (IOException error) { plugin.getLogger().warning("Could not close file handles: " + error.getMessage()); }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            headers(exchange);
            checkHost(exchange);
            checkOrigin(exchange, false);
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                api(exchange, path);
            } else {
                staticResource(exchange, path);
            }
        } catch (PanelFiles.ConflictException conflict) {
            error(exchange, 409, conflict.getMessage());
        } catch (SecurityException forbidden) {
            error(exchange, 403, forbidden.getMessage());
        } catch (IllegalArgumentException badInput) {
            error(exchange, 400, badInput.getMessage());
        } catch (TimeoutException timeout) {
            error(exchange, 504, "The server action has not completed. Check the console before retrying; it may still succeed.");
        } catch (Exception failure) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Panel request failed", failure);
            error(exchange, 500, "The panel could not complete this request");
        } finally {
            exchange.close();
        }
    }

    private void api(HttpExchange exchange, String path) throws Exception {
        String method = exchange.getRequestMethod();
        if (method.equals("OPTIONS") || method.equals("TRACE")) {
            error(exchange, 405, "Method not allowed");
            return;
        }
        if (path.equals("/api/status") && method.equals("GET")) {
            json(exchange, 200, Map.of("configured", auth.isConfigured()));
            return;
        }
        if (path.equals("/api/setup") && method.equals("POST")) {
            checkOrigin(exchange);
            JsonObject body = body(exchange);
            String password = required(body, "password", 128);
            try {
                auth.setPassword(required(body, "code", 100), password.toCharArray(),
                        exchange.getRemoteAddress().getAddress().getHostAddress());
            } catch (SecurityException invalid) {
                error(exchange, 403, invalid.getMessage());
                return;
            }
            json(exchange, 200, Map.of("ok", true));
            return;
        }
        if (path.equals("/api/setup-check") && method.equals("POST")) {
            checkOrigin(exchange);
            JsonObject body = body(exchange);
            String nonce = required(body, "nonce", 128);
            String step = required(body, "step", 16);
            if (step.equals("reach")) {
                if (!setupProbes.markReached(nonce)) {
                    error(exchange, 403, "Invalid or expired browser check link");
                    return;
                }
            } else if (step.equals("login")) {
                AuthService.Session session = auth.authenticate(sessionCookie(exchange));
                if (session == null) {
                    error(exchange, 401, "Sign in to complete the browser check");
                    return;
                }
                if (!auth.hasValidCsrf(session, exchange.getRequestHeaders().getFirst("X-CSRF-Token"))) {
                    error(exchange, 403, "Invalid request token. Refresh and sign in again.");
                    return;
                }
                if (!setupProbes.markLoggedIn(nonce)) {
                    error(exchange, 403, "Invalid or expired browser check link");
                    return;
                }
            } else {
                error(exchange, 400, "Unknown browser check step");
                return;
            }
            json(exchange, 200, Map.of("ok", true));
            return;
        }
        if (path.equals("/api/login") && method.equals("POST")) {
            checkOrigin(exchange);
            AuthService.Login login = auth.login(required(body(exchange), "password", 128).toCharArray(),
                    exchange.getRemoteAddress().getAddress().getHostAddress());
            if (login == null) {
                error(exchange, 401, "Password incorrect or too many attempts");
                return;
            }
            exchange.getResponseHeaders().set("Set-Cookie", "nab_session=" + login.token()
                    + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=7200");
            json(exchange, 200, Map.of("csrf", login.csrfToken()));
            return;
        }

        String token = sessionCookie(exchange);
        AuthService.Session session = auth.authenticate(token);
        if (session == null) {
            error(exchange, 401, "Sign in to continue");
            return;
        }
        if (!method.equals("GET") && !method.equals("HEAD")) {
            checkOrigin(exchange);
            if (!auth.hasValidCsrf(session, exchange.getRequestHeaders().getFirst("X-CSRF-Token"))) {
                error(exchange, 403, "Invalid request token. Refresh and sign in again.");
                return;
            }
        }
        if (path.equals("/api/session") && method.equals("GET")) {
            json(exchange, 200, Map.of("csrf", session.csrfToken()));
        } else if (path.equals("/api/logout") && method.equals("POST")) {
            auth.logout(token);
            exchange.getResponseHeaders().set("Set-Cookie", "nab_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
            json(exchange, 200, Map.of("ok", true));
        } else if (path.equals("/api/files") && method.equals("GET")) {
            json(exchange, 200, Map.of("entries", files.list(query(exchange, "path", ""))));
        } else if (path.equals("/api/file") && method.equals("GET")) {
            json(exchange, 200, files.readText(query(exchange, "path", "")));
        } else if (path.equals("/api/file") && method.equals("PUT")) {
            JsonObject body = body(exchange);
            json(exchange, 200, files.writeText(query(exchange, "path", ""),
                    required(body, "content", MAX_JSON_BYTES), required(body, "sha256", 128)));
        } else if (path.equals("/api/files") && method.equals("POST")) {
            JsonObject body = body(exchange);
            files.create(required(body, "path", 1024), body.has("directory") && body.get("directory").getAsBoolean());
            json(exchange, 201, Map.of("ok", true));
        } else if (path.equals("/api/move") && method.equals("POST")) {
            JsonObject body = body(exchange);
            files.move(required(body, "from", 1024), required(body, "to", 1024));
            json(exchange, 200, Map.of("ok", true));
        } else if (path.equals("/api/file") && method.equals("DELETE")) {
            String target = query(exchange, "path", "");
            if (!target.equals(exchange.getRequestHeaders().getFirst("X-Confirm-Path"))) {
                error(exchange, 400, "Confirm the exact path before deleting");
                return;
            }
            files.delete(target);
            json(exchange, 200, Map.of("ok", true));
        } else if (path.equals("/api/upload") && method.equals("POST")) {
            files.upload(query(exchange, "path", ""), exchange.getRequestBody());
            json(exchange, 201, Map.of("ok", true));
        } else if (path.equals("/api/download") && method.equals("GET")) {
            download(exchange, files.download(query(exchange, "path", "")));
        } else if (path.equals("/api/files/tar") && method.equals("POST")) {
            try (PanelFiles.DownloadArchive archive = files.tar(paths(body(exchange)))) {
                exchange.getResponseHeaders().set("Content-Type", "application/x-tar");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + archive.name() + "\"");
                exchange.sendResponseHeaders(200, archive.size());
                archive.input().transferTo(exchange.getResponseBody());
            }
        } else if (path.equals("/api/files/zip") && method.equals("POST")) {
            JsonObject body = body(exchange);
            files.zip(paths(body), required(body, "archive", 1024));
            json(exchange, 201, Map.of("ok", true));
        } else if (path.equals("/api/files/bulk") && method.equals("DELETE")) {
            JsonObject body = body(exchange);
            List<String> selected = paths(body);
            List<String> confirmed = paths(body, "confirmPaths");
            if (!selected.equals(confirmed)) throw new IllegalArgumentException("Confirm the exact selection before deleting");
            files.deleteMany(selected);
            json(exchange, 200, Map.of("ok", true, "deleted", selected.size()));
        } else if (path.equals("/api/zip") && method.equals("POST")) {
            JsonObject body = body(exchange);
            files.zip(required(body, "source", 1024), required(body, "archive", 1024));
            json(exchange, 201, Map.of("ok", true));
        } else if (path.equals("/api/unzip") && method.equals("POST")) {
            JsonObject body = body(exchange);
            files.unzip(required(body, "archive", 1024), required(body, "destination", 1024));
            json(exchange, 201, Map.of("ok", true));
        } else if (path.equals("/api/logs") && method.equals("GET")) {
            json(exchange, 200, Map.of("lines", logs.recent()));
        } else if (path.equals("/api/console/output") && method.equals("GET")) {
            json(exchange, 200, console.read(query(exchange, "cursor", "")));
        } else if (path.equals("/api/metrics") && method.equals("GET")) {
            PanelMetrics.History history = metrics.history(query(exchange, "range", "realtime"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("range", history.range());
            result.put("requestedFrom", history.requestedFrom());
            result.put("coverageStart", history.coverageStart());
            result.put("coverageEnd", history.coverageEnd());
            result.put("samples", history.samples());
            result.put("latest", metrics.latest());
            result.put("storageError", history.storageError());
            json(exchange, 200, result);
        } else if (path.equals("/api/backups") && method.equals("GET")) {
            json(exchange, 200, Map.of("backups", backups.list()));
        } else if (path.equals("/api/backups") && method.equals("POST")) {
            json(exchange, 202, backups.start(() -> {
                onMain(() -> { Bukkit.getWorlds().forEach(org.bukkit.World::save); return true; }, 120);
                return null;
            }));
        } else if (path.equals("/api/backups/latest") && method.equals("GET")) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("job", backups.latestJob());
            json(exchange, 200, result);
        } else if (path.equals("/api/backups/job") && method.equals("GET")) {
            json(exchange, 200, backups.job(query(exchange, "id", "")));
        } else if (path.equals("/api/backups/download") && method.equals("GET")) {
            download(exchange, backups.download(query(exchange, "name", "")));
        } else if (path.equals("/api/backups") && method.equals("DELETE")) {
            String name = query(exchange, "name", "");
            if (!name.equals(exchange.getRequestHeaders().getFirst("X-Confirm-Path"))) {
                error(exchange, 400, "Confirm the exact backup name before deleting");
                return;
            }
            backups.delete(name);
            json(exchange, 200, Map.of("ok", true));
        } else if (path.equals("/api/players") && method.equals("GET")) {
            json(exchange, 200, Map.of("players", onMain(() -> Bukkit.getOnlinePlayers().stream()
                    .map(player -> Map.of("name", player.getName(), "uuid", player.getUniqueId().toString(),
                            "op", player.isOp())).toList())));
        } else if (path.equals("/api/console") && method.equals("POST")) {
            String command = required(body(exchange), "command", 500).trim();
            if (command.startsWith("/") || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0 || command.isBlank()) {
                throw new IllegalArgumentException("Enter a single console command without a leading slash");
            }
            boolean accepted = onMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            plugin.getLogger().info("Panel console command dispatched: " + command.split("\\s+", 2)[0]);
            json(exchange, 200, Map.of("accepted", accepted));
        } else if (path.equals("/api/players") && method.equals("POST")) {
            JsonObject body = body(exchange);
            String action = required(body, "action", 32).toLowerCase(Locale.ROOT);
            String name = required(body, "name", 16);
            if (!List.of("op", "deop", "ban", "pardon", "whitelist-add", "whitelist-remove").contains(action)
                    || !name.matches("[A-Za-z0-9_]{3,16}")) {
                throw new IllegalArgumentException("Invalid player action or name");
            }
            String command = switch (action) {
                case "whitelist-add" -> "whitelist add " + name;
                case "whitelist-remove" -> "whitelist remove " + name;
                default -> action + " " + name;
            };
            boolean accepted = onMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            plugin.getLogger().info("Panel player action: " + command);
            json(exchange, 200, Map.of("accepted", accepted));
        } else {
            error(exchange, 404, "Unknown API endpoint");
        }
    }

    private <T> T onMain(java.util.concurrent.Callable<T> action) throws Exception {
        return onMain(action, 10);
    }

    private <T> T onMain(java.util.concurrent.Callable<T> action, long timeoutSeconds) throws Exception {
        return Bukkit.getScheduler().callSyncMethod(plugin, action).get(timeoutSeconds, TimeUnit.SECONDS);
    }

    private void staticResource(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            error(exchange, 405, "Method not allowed");
            return;
        }
        String resource = switch (path) {
            case "/", "/index.html" -> "/panel/index.html";
            case "/app.js" -> "/panel/app.js";
            case "/style.css" -> "/panel/style.css";
            default -> null;
        };
        if (resource == null) {
            error(exchange, 404, "Page not found");
            return;
        }
        try (InputStream input = PanelServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                error(exchange, 404, "Page not found");
                return;
            }
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", resource.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : resource.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void headers(HttpExchange exchange) {
        var output = exchange.getResponseHeaders();
        output.set("Cache-Control", "no-store");
        output.set("X-Content-Type-Options", "nosniff");
        output.set("X-Frame-Options", "DENY");
        output.set("Referrer-Policy", "no-referrer");
        output.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; "
                + "img-src 'self' data:; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
    }

    private void checkHost(HttpExchange exchange) {
        List<String> hosts = exchange.getRequestHeaders().get("Host");
        if (exchange.getRequestURI().isAbsolute() || hosts == null || hosts.size() != 1
                || !access.allowsHost(hosts.get(0), exchange.getRemoteAddress().getAddress())) {
            throw new SecurityException("Unknown panel host");
        }
    }

    private void checkOrigin(HttpExchange exchange) {
        checkOrigin(exchange, access.isPublic());
    }

    private void checkOrigin(HttpExchange exchange, boolean required) {
        List<String> origins = exchange.getRequestHeaders().get("Origin");
        if (origins != null && origins.size() != 1) {
            throw new SecurityException("Cross-site panel requests are blocked");
        }
        String origin = origins == null ? null : origins.get(0);
        if (!access.allowsOrigin(origin, exchange.getRemoteAddress().getAddress(), required)) {
            throw new SecurityException("Cross-site panel requests are blocked");
        }
    }

    private static String sessionCookie(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String entry : cookie.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.startsWith("nab_session=")) return trimmed.substring("nab_session=".length());
        }
        return null;
    }

    private static JsonObject body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES) throw new IllegalArgumentException("Request body is too large");
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
    }

    private static String required(JsonObject object, String name, int limit) {
        if (!object.has(name) || object.get(name).isJsonNull()) throw new IllegalArgumentException("Missing " + name);
        String value = object.get(name).getAsString();
        if (value.length() > limit) throw new IllegalArgumentException(name + " is too long");
        return value;
    }

    private static List<String> paths(JsonObject object) {
        return paths(object, "paths");
    }

    private static List<String> paths(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IllegalArgumentException("Select files or folders first");
        }
        JsonArray array = object.getAsJsonArray(key);
        if (array.isEmpty() || array.size() > 2_000) throw new IllegalArgumentException("Select between 1 and 2,000 items");
        List<String> paths = new ArrayList<>(array.size());
        for (var item : array) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Invalid file selection");
            }
            String value = item.getAsString();
            if (value.length() > 1024) throw new IllegalArgumentException("Selected path is too long");
            paths.add(value);
        }
        return paths;
    }

    private static String query(HttpExchange exchange, String key, String fallback) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return fallback;
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            if (split < 0) continue;
            if (URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8).equals(key)) {
                String value = URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
                if (value.length() > 1024) throw new IllegalArgumentException("Path is too long");
                return value;
            }
        }
        return fallback;
    }

    private void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void error(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange, status, Map.of("error", message == null ? "Request failed" : message));
    }

    private static void download(HttpExchange exchange, SecureFileRoot.OpenedFile source) throws IOException {
        try (source) {
            String safeName = source.name().replaceAll("[^A-Za-z0-9._-]", "_");
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
            exchange.sendResponseHeaders(200, 0);
            source.input().transferTo(exchange.getResponseBody());
        }
    }
}
