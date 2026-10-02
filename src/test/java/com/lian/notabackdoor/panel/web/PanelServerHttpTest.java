package com.lian.notabackdoor.panel.web;

import com.google.gson.JsonParser;
import com.lian.notabackdoor.panel.NotABackdoorPlugin;
import com.lian.notabackdoor.panel.files.PanelFiles;
import com.lian.notabackdoor.panel.security.AuthService;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real embedded HTTP listener without booting a Minecraft server. */
class PanelServerHttpTest {
    @TempDir Path directory;

    @Test
    void publicListenerRejectsUnexpectedHostAndOrigin() throws Exception {
        try (Harness server = new Harness(directory, new SetupProbeService())) {
            assertEquals(200, server.send("GET", "/api/status", server.host(), null).status());
            assertEquals(403, server.send("GET", "/api/status", "other.example.test:" + server.port, null).status());
            assertEquals(403, server.send("GET", "/api/status", "panel.example.test:" + (server.port + 1), null).status());
            assertEquals(200, server.send("GET", "/api/status", "localhost:" + server.port, null).status());
            assertEquals(403, server.send("GET", "/api/status", server.host(), "http://other.example.test:" + server.port).status());

            String body = "{\"nonce\":\"bad\",\"step\":\"reach\"}";
            assertEquals(403, server.send("POST", "/api/setup-check", server.host(), null, body).status());
            assertEquals(403, server.send("POST", "/api/setup-check", server.host(),
                    "http://other.example.test:" + server.port, body).status());
            assertEquals(403, server.send("POST", "/api/setup-check", server.host(), server.origin(), body).status());
            // The JDK may reject duplicate Host headers before they reach our handler.
            assertNotEquals(200, server.sendWithHeaders("GET", "/api/status",
                    List.of("Host: " + server.host(), "Host: " + server.host()), "").status());
        }
    }

    @Test
    void browserReachNeedsNonceAndLoginNeedsSessionAndCsrf() throws Exception {
        MutableClock clock = new MutableClock();
        SetupProbeService probes = new SetupProbeService(clock);
        try (Harness server = new Harness(directory, probes)) {
            UUID operator = UUID.randomUUID();
            String nonce = probes.issue(operator);
            String reach = "{\"nonce\":\"" + nonce + "\",\"step\":\"reach\"}";
            String loginCheck = "{\"nonce\":\"" + nonce + "\",\"step\":\"login\"}";
            assertEquals(200, server.send("POST", "/api/setup-check", server.host(), server.origin(), reach).status());
            assertTrue(probes.reached(operator));
            assertEquals(401, server.send("POST", "/api/setup-check", server.host(), server.origin(), loginCheck).status());
            assertFalse(probes.loggedIn(operator));

            server.auth.setPassword(server.auth.firstRunCode().value(), "long test password".toCharArray());
            Reply login = server.send("POST", "/api/login", server.host(), server.origin(),
                    "{\"password\":\"long test password\"}");
            assertEquals(200, login.status());
            String cookie = login.header("set-cookie").split(";", 2)[0];
            String csrf = JsonParser.parseString(login.body()).getAsJsonObject().get("csrf").getAsString();

            assertEquals(403, server.sendWithHeaders("POST", "/api/setup-check", List.of(
                    "Host: " + server.host(), "Origin: " + server.origin(), "Cookie: " + cookie), loginCheck).status());
            assertFalse(probes.loggedIn(operator));
            assertEquals(200, server.sendWithHeaders("POST", "/api/setup-check", List.of(
                    "Host: " + server.host(), "Origin: " + server.origin(), "Cookie: " + cookie,
                    "X-CSRF-Token: " + csrf), loginCheck).status());
            assertTrue(probes.loggedIn(operator));

            clock.advance(Duration.ofMinutes(11));
            assertEquals(403, server.send("POST", "/api/setup-check", server.host(), server.origin(), reach).status());
            assertTrue(probes.reached(operator)); // Completed check remains visible after its URL expires.
        }
    }

    @Test
    void failedSetupRequestsFromOneAddressDoNotBlockAnother() throws Exception {
        try (Harness server = new Harness(directory, new SetupProbeService())) {
            String code = server.auth.firstRunCode().value();
            String wrong = "{\"code\":\"wrong\",\"password\":\"long test password\"}";
            String correct = "{\"code\":\"" + code + "\",\"password\":\"long test password\"}";
            for (int attempt = 0; attempt < 5; attempt++) {
                assertEquals(403, server.send("POST", "/api/setup", server.host(), server.origin(), wrong).status());
            }
            Reply blocked = server.send("POST", "/api/setup", server.host(), server.origin(), correct);
            assertEquals(403, blocked.status());
            assertTrue(blocked.body().contains("Too many setup attempts"));
            assertFalse(server.auth.isConfigured());
            assertEquals(200, server.sendFrom("127.0.0.2", "POST", "/api/setup", server.host(),
                    server.origin(), correct).status());
            assertTrue(server.auth.isConfigured());
        }
    }

    private static final class Harness implements AutoCloseable {
        final int port;
        final AuthService auth;
        final PanelServer panel;

        Harness(Path root, SetupProbeService probes) throws Exception {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                Assumptions.assumeTrue(stream instanceof SecureDirectoryStream<?>,
                        "Embedded panel file access requires race-safe directory handles");
            }
            Path data = root.resolve("plugins").resolve("NotABackdoor");
            Files.createDirectories(data);
            this.port = availablePort();
            this.auth = new AuthService(data);
            NotABackdoorPlugin plugin = emptyPlugin(data.toFile());
            PanelFiles files = new PanelFiles(root);
            PanelServer prepared;
            try {
                prepared = new PanelServer(plugin, auth, files,
                        PanelAccess.publicHttp("http://panel.example.test:" + port, port), probes);
            } catch (Exception failure) {
                files.close();
                throw failure;
            }
            this.panel = prepared;
            panel.start();
        }

        String host() { return "panel.example.test:" + port; }
        String origin() { return "http://" + host(); }

        Reply send(String method, String path, String host, String origin) throws Exception {
            return send(method, path, host, origin, "");
        }

        Reply send(String method, String path, String host, String origin, String body) throws Exception {
            return sendFrom("127.0.0.1", method, path, host, origin, body);
        }

        Reply sendFrom(String sourceAddress, String method, String path, String host, String origin, String body) throws Exception {
            List<String> headers = new ArrayList<>();
            headers.add("Host: " + host);
            if (origin != null) headers.add("Origin: " + origin);
            return sendWithHeadersFrom(sourceAddress, method, path, headers, body);
        }

        Reply sendWithHeaders(String method, String path, List<String> headers, String body) throws Exception {
            return sendWithHeadersFrom("127.0.0.1", method, path, headers, body);
        }

        Reply sendWithHeadersFrom(String sourceAddress, String method, String path,
                                  List<String> headers, String body) throws Exception {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (Socket socket = new Socket()) {
                socket.bind(new InetSocketAddress(sourceAddress, 0));
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                socket.setSoTimeout(5000);
                OutputStream output = socket.getOutputStream();
                StringBuilder request = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
                for (String header : headers) request.append(header).append("\r\n");
                request.append("Connection: close\r\n")
                        .append("Content-Type: application/json\r\n")
                        .append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
                output.write(request.toString().getBytes(StandardCharsets.US_ASCII));
                output.write(bytes);
                output.flush();

                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String statusLine = input.readLine();
                assertNotNull(statusLine);
                int status = Integer.parseInt(statusLine.split(" ", 3)[1]);
                List<String> responseHeaders = new ArrayList<>();
                String line;
                while ((line = input.readLine()) != null && !line.isEmpty()) responseHeaders.add(line);
                int length = responseHeaders.stream().filter(h -> h.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                        .mapToInt(h -> Integer.parseInt(h.split(":", 2)[1].trim())).findFirst().orElse(0);
                char[] chars = new char[length];
                int offset = 0;
                while (offset < length) {
                    int read = input.read(chars, offset, length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                return new Reply(status, responseHeaders, new String(chars, 0, offset));
            }
        }

        @Override public void close() { panel.close(); }
    }

    private record Reply(int status, List<String> headers, String body) {
        String header(String name) {
            return headers.stream().filter(h -> h.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + ":"))
                    .map(h -> h.split(":", 2)[1].trim()).findFirst().orElse(null);
        }
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    /** Bukkit constructs JavaPlugin through its own loader; this test only needs its data-folder field. */
    private static NotABackdoorPlugin emptyPlugin(File dataFolder) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        NotABackdoorPlugin plugin = (NotABackdoorPlugin) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, NotABackdoorPlugin.class);
        Field folderField = JavaPlugin.class.getDeclaredField("dataFolder");
        folderField.setAccessible(true);
        folderField.set(plugin, dataFolder);
        Field loggerField = JavaPlugin.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(plugin, Logger.getLogger("NotABackdoorHttpTest"));
        return plugin;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
