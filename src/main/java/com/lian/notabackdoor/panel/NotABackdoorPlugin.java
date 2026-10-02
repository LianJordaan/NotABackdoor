package com.lian.notabackdoor.panel;

import com.lian.notabackdoor.panel.relay.RelayClient;
import com.lian.notabackdoor.panel.security.AuthService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public final class NotABackdoorPlugin extends JavaPlugin {
    private AuthService auth;
    private PanelLifecycle panel;
    private RelayClient relay;
    private SetupGuide setupGuide;
    private volatile SetupGuide.Access setupAccess;

    @Override
    public void onEnable() {
        try {
            migrateLegacyConfiguration();
            saveDefaultConfig();
            int port = getConfig().getInt("panel.port", 8127);
            Path data = getDataFolder().toPath();
            auth = new AuthService(data);
            setupGuide = new SetupGuide(this, auth, () -> setupAccess);
            getServer().getPluginManager().registerEvents(setupGuide, this);
            panel = new PanelLifecycle(this, auth);
            panel.start();
            setSetupAccess(new SetupGuide.Access() {
                @Override public String accessMode() { return panel.accessMode(); }
                @Override public String advertisedOrigin() { return panel.advertisedOrigin(); }
                @Override public String localOrigin() { return panel.localOrigin(); }
                @Override public SetupGuide.Result applyLocal() {
                    PanelLifecycle.Result result = panel.applyLocal();
                    return new SetupGuide.Result(result.success(), result.message());
                }
                @Override public SetupGuide.Result applyPublic(String origin) {
                    PanelLifecycle.Result result = panel.applyPublic(origin);
                    return new SetupGuide.Result(result.success(), result.message());
                }
                @Override public SetupGuide.Check localHealth() {
                    PanelLifecycle.Health result = panel.localHealth();
                    return new SetupGuide.Check(result.reachable(), result.detail());
                }
                @Override public String createBrowserProofUrl(java.util.UUID playerId) {
                    return panel.createBrowserProofUrl(playerId);
                }
                @Override public boolean browserProofReceived(java.util.UUID playerId) {
                    return panel.browserProofReceived(playerId);
                }
                @Override public boolean browserLoginReceived(java.util.UUID playerId) {
                    return panel.browserLoginReceived(playerId);
                }
            });
            getLogger().info("Panel listening at " + panel.advertisedOrigin() + "/");
            if (!auth.isConfigured()) {
                getLogger().info(getServer().getOnlineMode()
                        ? "An operator can use /nab in game for first-run setup; the console can also run 'nab setup'."
                        : "Run 'nab setup' from the server console to create the first panel password.");
            }
            try {
                relay = new RelayClient(data, port, getLogger());
                getLogger().info("Optional HTTPS relay: " + relay.status());
            } catch (IOException relayFailure) {
                getLogger().warning("Optional relay state could not load; local panel and SSH access remain available: "
                        + relayFailure.getMessage());
            }
        } catch (Exception failure) {
            getLogger().severe("Panel startup failed safely: " + failure.getMessage());
            if (panel != null) {
                panel.close();
                panel = null;
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (relay != null) {
            relay.close();
            relay = null;
        }
        if (panel != null) {
            panel.close();
            panel = null;
        }
        setupAccess = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            if (setupGuide == null) {
                player.sendMessage("The panel setup guide is unavailable.");
                return true;
            }
            if (args.length == 0 || (args.length == 1 && "setup".equalsIgnoreCase(args[0]))) {
                setupGuide.open(player);
            } else if (args.length == 1 && "status".equalsIgnoreCase(args[0])) {
                setupGuide.status(player);
            } else {
                player.sendMessage("Usage: /nab [setup|status]");
            }
            return true;
        }
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("This command is available only in the server console.");
            return true;
        }
        if (args.length == 1 && "status".equalsIgnoreCase(args[0])) {
            sender.sendMessage("Panel " + (auth != null && auth.isConfigured() ? "configured" : "not configured") + ".");
            SetupGuide.Access access = setupAccess;
            sender.sendMessage(access == null ? "Panel access service unavailable."
                    : "Access: " + access.accessMode() + " at " + access.advertisedOrigin());
            return true;
        }
        if (args.length == 1 && "setup".equalsIgnoreCase(args[0])) {
            if (auth == null) {
                sender.sendMessage("The panel is not running.");
            } else {
                AuthService.FirstRunCode firstRun = auth.firstRunCode();
                String code = firstRun == null ? auth.issueSetupCode() : firstRun.value();
                sender.sendMessage("One-time panel setup code (15 minutes): " + code);
                sender.sendMessage("Enter this code on the panel. Keep the server console private.");
            }
            return true;
        }
        if (args.length >= 2 && "access".equalsIgnoreCase(args[0])) {
            SetupGuide.Access access = setupAccess;
            if (access == null) {
                sender.sendMessage("The panel access service is unavailable. No setting was changed.");
                return true;
            }
            SetupGuide.Result result;
            if (args.length == 2 && "local".equalsIgnoreCase(args[1])) {
                result = access.applyLocal();
            } else if (args.length == 4 && "public".equalsIgnoreCase(args[1])
                    && SetupGuide.validPublicOrigin(args[2]) && "confirm".equalsIgnoreCase(args[3])) {
                result = access.applyPublic(args[2]);
            } else {
                sender.sendMessage("Public HTTP exposes passwords and sessions in transit.");
                sender.sendMessage("Usage: nab access local | nab access public http://host:port confirm");
                return true;
            }
            sender.sendMessage(result.message());
            return true;
        }
        if (args.length >= 2 && "relay".equalsIgnoreCase(args[0])) {
            if (relay == null) {
                sender.sendMessage("The optional relay could not load. The local panel still works through SSH.");
                return true;
            }
            if (args.length == 2 && "status".equalsIgnoreCase(args[1])) {
                sender.sendMessage(relay.status());
                return true;
            }
            if (args.length == 2 && "revoke".equalsIgnoreCase(args[1])) {
                try {
                    sender.sendMessage(relay.revoke());
                } catch (IOException failure) {
                    sender.sendMessage("Could not queue relay revocation. Check the local relay state file and retry.");
                }
                return true;
            }
            if ((args.length == 2 || args.length == 3) && "pair".equalsIgnoreCase(args[1])) {
                String relayOrigin = args.length == 3 ? args[2]
                        : getConfig().getString("relay.origin", "").trim();
                if (relayOrigin.isBlank()) {
                    sender.sendMessage("No relay address is configured. Set relay.origin or use 'nab relay pair <https-origin>'.");
                    return true;
                }
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    String[] lines;
                    try {
                        RelayClient.Pairing pairing = relay.pair(relayOrigin);
                        lines = new String[]{
                                "Open this one-time HTTPS pairing link: " + pairing.url(),
                                "Enter this separate console pairing code: " + pairing.code(),
                                "The link expires at " + pairing.expiresAt() + ".",
                                auth.isConfigured()
                                        ? "After pairing, sign in with your existing panel password."
                                        : "After pairing, run 'nab setup' here for the panel password setup code."
                        };
                    } catch (Exception failure) {
                        lines = new String[]{"Relay pairing failed: " + failure.getMessage()
                                + ". Local access through SSH still works."};
                    }
                    String[] resultLines = lines;
                    getServer().getScheduler().runTask(this, () -> {
                        for (String line : resultLines) sender.sendMessage(line);
                    });
                });
                return true;
            }
        }
        sender.sendMessage("Usage: nab setup | nab status | nab access local | nab access public http://host:port confirm | nab relay pair [https-origin] | nab relay status | nab relay revoke (console only)");
        return true;
    }

    /** Installed by the panel lifecycle after its listener has started successfully. */
    void setSetupAccess(SetupGuide.Access access) {
        setupAccess = access;
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
