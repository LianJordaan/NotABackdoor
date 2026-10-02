package com.lian.notabackdoor.panel.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lian.notabackdoor.panel.NotABackdoorPlugin;
import com.lian.notabackdoor.panel.files.PanelFiles;
import com.lian.notabackdoor.panel.security.AuthService;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the authenticated chart and actual Minecraft console through the embedded listener. */
class PanelObservabilityHttpTest {
    @TempDir Path root;

    @Test
    void requiresSignInAndStreamsLogFromCursorWhileReturningMetricHistory() throws Exception {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            Assumptions.assumeTrue(stream instanceof SecureDirectoryStream<?>);
        }
        Path latest = Files.createDirectories(root.resolve("logs")).resolve("latest.log");
        Files.writeString(latest, "[00:00:01] [Server thread/INFO]: Starting minecraft server\n");
        Path data = Files.createDirectories(root.resolve("plugins/NotABackdoor"));
        AuthService auth = new AuthService(data);
        auth.setPassword(auth.firstRunCode().value(), "long observability test password".toCharArray());
        int port;
        try (ServerSocket available = new ServerSocket(0)) { port = available.getLocalPort(); }
        HttpClient client = HttpClient.newHttpClient();
        try (PanelServer panel = new PanelServer(emptyPlugin(data.toFile()), auth,
                new PanelFiles(root), PanelAccess.local(port))) {
            panel.start();
            URI base = URI.create("http://127.0.0.1:" + port);
            assertEquals(401, get(client, base.resolve("/api/console/output"), null).statusCode());
            assertEquals(401, get(client, base.resolve("/api/metrics?range=1w"), null).statusCode());

            HttpRequest login = HttpRequest.newBuilder(base.resolve("/api/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"long observability test password\"}"))
                    .build();
            HttpResponse<String> signedIn = client.send(login, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, signedIn.statusCode());
            String cookie = signedIn.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

            JsonObject first = json(get(client, base.resolve("/api/console/output"), cookie));
            assertTrue(first.get("available").getAsBoolean());
            assertEquals(1, first.getAsJsonArray("lines").size());
            assertTrue(first.getAsJsonArray("lines").get(0).getAsString().contains("Starting minecraft server"));
            String cursor = first.get("cursor").getAsString();
            assertEquals(0, json(get(client, base.resolve("/api/console/output?cursor=" + cursor), cookie))
                    .getAsJsonArray("lines").size());

            Files.writeString(latest, "[00:00:02] [Server thread/INFO]: Done\n", StandardOpenOption.APPEND);
            JsonObject next = json(get(client, base.resolve("/api/console/output?cursor=" + cursor), cookie));
            assertEquals(1, next.getAsJsonArray("lines").size());
            assertTrue(next.getAsJsonArray("lines").get(0).getAsString().contains("Done"));
            assertFalse(next.get("reset").getAsBoolean());

            Field metricsField = PanelServer.class.getDeclaredField("metrics");
            metricsField.setAccessible(true);
            PanelMetrics metrics = (PanelMetrics) metricsField.get(panel);
            long now = System.currentTimeMillis();
            metrics.record(new PanelMetrics.Sample(now, 18.0, 19.9, 12.5,
                    1_000_000, 4_000_000, 3));
            JsonObject history = json(get(client, base.resolve("/api/metrics?range=1w"), cookie));
            assertEquals("1w", history.get("range").getAsString());
            assertEquals(1, history.getAsJsonArray("samples").size());
            assertEquals(18.0, history.getAsJsonObject("latest").get("cpuPercent").getAsDouble());
            assertEquals(3, history.getAsJsonObject("latest").get("players").getAsInt());
            assertEquals(400, get(client, base.resolve("/api/metrics?range=invalid"), cookie).statusCode());
        }
    }

    private static JsonObject json(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static HttpResponse<String> get(HttpClient client, URI uri, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Bukkit normally constructs JavaPlugin; this listener test only needs its data folder/logger. */
    private static NotABackdoorPlugin emptyPlugin(File dataFolder) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        NotABackdoorPlugin plugin = (NotABackdoorPlugin) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, NotABackdoorPlugin.class);
        Field folder = JavaPlugin.class.getDeclaredField("dataFolder");
        folder.setAccessible(true);
        folder.set(plugin, dataFolder);
        Field logger = JavaPlugin.class.getDeclaredField("logger");
        logger.setAccessible(true);
        logger.set(plugin, Logger.getLogger("NotABackdoorObservabilityHttpTest"));
        return plugin;
    }
}
