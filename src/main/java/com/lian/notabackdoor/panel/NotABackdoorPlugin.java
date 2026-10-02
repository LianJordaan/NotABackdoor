package com.lian.notabackdoor.panel;

import com.lian.notabackdoor.panel.files.PanelFiles;
import com.lian.notabackdoor.panel.security.AuthService;
import com.lian.notabackdoor.panel.web.PanelServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public final class NotABackdoorPlugin extends JavaPlugin {
    private AuthService auth;
    private PanelServer panel;

    @Override
    public void onEnable() {
        try {
            migrateLegacyConfiguration();
            saveDefaultConfig();
            String bind = getConfig().getString("panel.bind", "127.0.0.1");
            if (!"127.0.0.1".equals(bind)) {
                throw new IllegalArgumentException("The embedded panel must bind to 127.0.0.1; use an SSH tunnel for remote access");
            }
            int port = getConfig().getInt("panel.port", 8127);
            if (port < 1024 || port > 65535) {
                throw new IllegalArgumentException("panel.port must be between 1024 and 65535");
            }
            Path data = getDataFolder().toPath();
            auth = new AuthService(data);
            // Bukkit's world container can be a custom subdirectory. The server process
            // directory contains plugins/, server.properties, and other panel files.
            PanelFiles files = new PanelFiles(Path.of("").toAbsolutePath());
            try {
                panel = new PanelServer(this, auth, files, port);
                panel.start();
            } catch (Exception startup) {
                if (panel != null) {
                    panel.close();
                    panel = null;
                } else {
                    files.close();
                }
                throw startup;
            }
            getLogger().info("Panel listening at http://127.0.0.1:" + port + "/");
            if (!auth.isConfigured()) {
                getLogger().info("Run 'nab setup' from the server console to create the first panel password.");
            }
        } catch (Exception failure) {
            getLogger().severe("Panel startup failed safely: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (panel != null) {
            panel.close();
            panel = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("This command is available only in the server console.");
            return true;
        }
        if (args.length == 1 && "setup".equalsIgnoreCase(args[0])) {
            if (auth == null) {
                sender.sendMessage("The panel is not running.");
            } else {
                sender.sendMessage("One-time panel setup code (15 minutes): " + auth.issueSetupCode());
                sender.sendMessage("Open http://127.0.0.1:" + getConfig().getInt("panel.port", 8127)
                        + "/ through an SSH tunnel and enter this code on the setup screen.");
            }
            return true;
        }
        sender.sendMessage("Usage: nab setup (server console only)");
        return true;
    }

    private void migrateLegacyConfiguration() throws IOException {
        Path data = getDataFolder().toPath();
        Path config = data.resolve("config.yml");
        if (!Files.exists(config)) return;
        String original = Files.readString(config);
        if (original.contains("config-version:")) return;
        Path backup = data.resolve("config.yml.legacy-" + Instant.now().toEpochMilli() + ".bak");
        Files.copy(config, backup, StandardCopyOption.COPY_ATTRIBUTES);
        Files.delete(config);
        getLogger().warning("Legacy configuration backed up to " + backup.getFileName()
                + ". The insecure public HTTP and password-link settings are no longer used.");
    }
}
