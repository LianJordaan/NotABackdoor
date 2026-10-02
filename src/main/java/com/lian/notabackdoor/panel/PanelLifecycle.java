package com.lian.notabackdoor.panel;

import com.lian.notabackdoor.panel.files.PanelFiles;
import com.lian.notabackdoor.panel.security.AuthService;
import com.lian.notabackdoor.panel.web.PanelAccess;
import com.lian.notabackdoor.panel.web.PanelServer;
import com.lian.notabackdoor.panel.web.SetupProbeService;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the embedded listener and applies access changes without restarting Paper. */
public final class PanelLifecycle implements AutoCloseable {
    private final JavaPlugin plugin;
    private final AuthService auth;
    private final SetupProbeService setupProbes = new SetupProbeService();
    private final AtomicBoolean healthInFlight = new AtomicBoolean();
    private volatile Health health = new Health(false, "Panel health has not been checked yet");
    private volatile Instant healthCheckedAt = Instant.EPOCH;
    private PanelServer panel;
    private PanelAccess access;

    public record Result(boolean success, String message) { }
    public record Health(boolean reachable, String detail) { }

    public PanelLifecycle(JavaPlugin plugin, AuthService auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    public synchronized void start() throws IOException {
        if (panel != null) throw new IllegalStateException("Panel listener already started");
        PanelAccess configured = configuredAccess();
        panel = open(configured);
        access = configured;
        if (plugin.getConfig().getInt("config-version", 2) < 3) {
            Path configFile = plugin.getDataFolder().toPath().resolve("config.yml");
            try {
                persist(configured, configFile, Files.readAllBytes(configFile));
            } catch (IOException migrationFailure) {
                panel.close();
                panel = null;
                access = null;
                throw migrationFailure;
            }
        }
        refreshHealth();
    }

    public synchronized Result applyPublic(String origin) {
        PanelAccess next;
        try {
            URI parsed = URI.create(origin);
            next = PanelAccess.publicHttp(origin, parsed.getPort());
        } catch (RuntimeException invalid) {
            return new Result(false, "Use one exact HTTP address, such as http://panel.example.com:8127");
        }
        return apply(next);
    }

    public synchronized Result applyLocal() {
        if (access == null) return new Result(false, "The panel is not running");
        return apply(PanelAccess.local(access.port()));
    }

    public synchronized String accessMode() {
        return access == null ? "offline" : access.isPublic() ? "public_http" : "local";
    }

    public synchronized String advertisedOrigin() {
        return access == null ? "" : access.advertisedOrigin();
    }

    public synchronized String localOrigin() {
        return access == null ? "" : "http://127.0.0.1:" + access.port();
    }

    public synchronized PanelAccess access() {
        return access;
    }

    public Health localHealth() {
        PanelAccess current = access();
        if (current == null) return new Health(false, "The panel is not running");
        Health checked = checkHealth(current);
        if (access() == current) {
            health = checked;
            healthCheckedAt = Instant.now();
        }
        return checked;
    }

    public synchronized String createBrowserProofUrl(java.util.UUID playerId) {
        PanelAccess current = access;
        if (current == null) return "";
        return current.advertisedOrigin() + "/#nab-check=" + setupProbes.issue(playerId);
    }

    public boolean browserProofReceived(java.util.UUID playerId) {
        return setupProbes.reached(playerId);
    }

    public boolean browserLoginReceived(java.util.UUID playerId) {
        return setupProbes.loggedIn(playerId);
    }

    private PanelAccess configuredAccess() {
        FileConfiguration config = plugin.getConfig();
        int port = config.getInt("panel.port", 8127);
        String mode = config.getString("panel.access-mode", "local");
        if (mode == null) mode = "local";
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "local" -> PanelAccess.local(port);
            case "public_http" -> PanelAccess.publicHttp(
                    config.getString("panel.advertised-origin", ""), port);
            default -> throw new IllegalArgumentException("Unknown panel access mode");
        };
    }

    private Result apply(PanelAccess next) {
        if (panel == null || access == null) return new Result(false, "The panel is not running");
        if (access.mode() == next.mode() && access.advertisedOrigin().equals(next.advertisedOrigin())
                && access.port() == next.port()) {
            return new Result(true, "The panel already uses that address");
        }
        PanelAccess previous = access;
        Path configFile = plugin.getDataFolder().toPath().resolve("config.yml");
        byte[] previousConfig;
        try {
            previousConfig = Files.readAllBytes(configFile);
        } catch (IOException unreadable) {
            return new Result(false, "Cannot back up the panel configuration: " + unreadable.getMessage());
        }
        panel.close();
        panel = null;
        PanelServer replacement = null;
        boolean persistenceAttempted = false;
        try {
            replacement = open(next);
            persistenceAttempted = true;
            persist(next, configFile, previousConfig);
            panel = replacement;
            access = next;
            auth.revokeSessions();
            auth.revokeSetupCode();
            setupProbes.clear();
            health = new Health(false, "Checking the new panel listener");
            healthCheckedAt = Instant.EPOCH;
            refreshHealth();
            return new Result(true, "Panel listening at " + next.advertisedOrigin());
        } catch (Exception failure) {
            if (replacement != null) replacement.close();
            Exception configFailure = null;
            if (persistenceAttempted) {
                try {
                    if (!java.util.Arrays.equals(Files.readAllBytes(configFile), previousConfig)) {
                        restoreConfig(configFile, previousConfig);
                    }
                    plugin.reloadConfig();
                } catch (Exception restoreFailure) {
                    configFailure = restoreFailure;
                    plugin.getLogger().severe("Could not restore the prior panel configuration: "
                            + restoreFailure.getMessage());
                }
            }
            try {
                panel = open(previous);
                access = previous;
                refreshHealth();
            } catch (Exception restoreFailure) {
                plugin.getLogger().severe("Could not restore the previous panel listener: "
                        + restoreFailure.getMessage());
                throw new IllegalStateException("Panel change failed and the old listener could not be restored",
                        restoreFailure);
            }
            if (configFailure != null) {
                return new Result(false, "Previous listener restored, but config.yml could not be restored: "
                        + configFailure.getMessage());
            }
            return new Result(false, "Panel address failed; previous listener restored: " + failure.getMessage());
        }
    }

    private PanelServer open(PanelAccess next) throws IOException {
        PanelFiles files = new PanelFiles(Path.of("").toAbsolutePath());
        PanelServer created = null;
        try {
            created = new PanelServer(plugin, auth, files, next, setupProbes);
            created.start();
            return created;
        } catch (IOException | RuntimeException failure) {
            if (created != null) created.close();
            else files.close();
            throw failure;
        }
    }

    private void persist(PanelAccess next, Path configFile, byte[] previousConfig) throws IOException {
        FileConfiguration config = plugin.getConfig();
        int oldVersion = config.getInt("config-version", 2);
        if (oldVersion < 3) {
            Path backup = configFile.resolveSibling("config.yml.v2.bak");
            if (!Files.exists(backup)) Files.write(backup, previousConfig);
        }
        config.set("config-version", 3);
        config.set("panel.access-mode", next.isPublic() ? "public_http" : "local");
        config.set("panel.advertised-origin", next.isPublic() ? next.advertisedOrigin() : "");
        config.set("panel.bind", next.bindAddress());
        config.set("panel.port", next.port());
        atomicWrite(configFile, config.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        plugin.reloadConfig();
    }

    private static void restoreConfig(Path configFile, byte[] previous) throws IOException {
        atomicWrite(configFile, previous);
    }

    private static void atomicWrite(Path path, byte[] data) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".panel-config-", ".tmp");
        try {
            Files.write(temporary, data);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void refreshHealth() {
        PanelAccess current = access();
        if (current == null || healthInFlight.get()
                || Instant.now().minusSeconds(5).isBefore(healthCheckedAt)) return;
        if (!healthInFlight.compareAndSet(false, true)) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Health checked = checkHealth(current);
                    if (access() == current) {
                        health = checked;
                        healthCheckedAt = Instant.now();
                    }
                } finally {
                    healthInFlight.set(false);
                }
            });
        } catch (RuntimeException schedulingFailure) {
            healthInFlight.set(false);
            health = new Health(false, "Local health check could not start: " + schedulingFailure.getMessage());
        }
    }

    private static Health checkHealth(PanelAccess current) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(
                    "http://127.0.0.1:" + current.port() + "/api/status").toURL().openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            byte[] body = status == 200 ? connection.getInputStream().readNBytes(1024) : new byte[0];
            return new Health(status == 200 && new String(body,
                    java.nio.charset.StandardCharsets.UTF_8).contains("configured"),
                    status == 200 ? "Local panel HTTP responded" : "Local panel returned HTTP " + status);
        } catch (Exception failure) {
            return new Health(false, "Local panel did not respond: " + failure.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override
    public synchronized void close() {
        if (panel != null) {
            panel.close();
            panel = null;
        }
        access = null;
    }
}
