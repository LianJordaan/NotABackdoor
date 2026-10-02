package com.lian.notabackdoor.panel;

import com.lian.notabackdoor.panel.security.AuthService;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Private, operator-only first-run guide. Network changes are delegated to the panel lifecycle. */
public final class SetupGuide implements Listener {
    public interface Access {
        String accessMode();
        String advertisedOrigin();
        String localOrigin();
        Result applyLocal();
        Result applyPublic(String exactHttpOrigin);
        Check localHealth();
        String createBrowserProofUrl(UUID playerId);
        boolean browserProofReceived(UUID playerId);
        boolean browserLoginReceived(UUID playerId);
    }

    public record Result(boolean success, String message) { }
    public record Check(boolean reachable, String detail) { }

    enum Page { GUIDE, ACCESS, PUBLIC_CONFIRM }
    private record PendingOrigin(Instant expiresAt) { }

    private static final String PERMISSION = "notabackdoor.admin";
    private static final Duration INPUT_LIFETIME = Duration.ofMinutes(2);
    private final JavaPlugin plugin;
    private final AuthService auth;
    private final Supplier<Access> accessSupplier;
    private final Map<UUID, PendingOrigin> pendingOrigins = new ConcurrentHashMap<>();

    public SetupGuide(JavaPlugin plugin, AuthService auth, Supplier<Access> accessSupplier) {
        this.plugin = plugin;
        this.auth = auth;
        this.accessSupplier = accessSupplier;
    }

    public static boolean authorized(Player player) {
        return player.isOp() && player.hasPermission(PERMISSION);
    }

    public void open(Player player) {
        if (!authorized(player)) {
            player.sendMessage(ChatColor.RED + "Only a permitted server operator can manage NotABackdoor.");
            return;
        }
        openPage(player, Page.GUIDE);
    }

    public void status(Player player) {
        if (!authorized(player)) {
            player.sendMessage(ChatColor.RED + "Only a permitted server operator can manage NotABackdoor.");
            return;
        }
        Access access = accessSupplier.get();
        player.sendMessage(ChatColor.AQUA + "NotABackdoor " + (auth.isConfigured() ? "is configured" : "needs setup") + ".");
        if (access == null) {
            player.sendMessage(ChatColor.RED + "The panel access service is unavailable.");
            return;
        }
        player.sendMessage(ChatColor.GRAY + "Access: " + access.accessMode() + " | " + access.advertisedOrigin());
        player.sendMessage(ChatColor.GRAY + "Browser check: "
                + (access.browserProofReceived(player.getUniqueId()) ? "received" : "not yet received"));
        player.sendMessage(ChatColor.GRAY + "Browser sign-in: "
                + (access.browserLoginReceived(player.getUniqueId()) ? "confirmed" : "not yet confirmed"));
        checkConnection(player, false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && !auth.isConfigured() && authorized(player)) {
                player.sendMessage(ChatColor.GOLD + "NotABackdoor has not been set up yet.");
                clickable(player, ChatColor.AQUA + "[Open the private setup guide]", "/nab",
                        ClickEvent.Action.RUN_COMMAND);
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GuideHolder holder)) return;
        // Cancel *all* clicks while this menu is open: shift-click, number keys, doubles and
        // bottom-inventory actions can otherwise transfer items into the top inventory.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())) return;
        if (!authorized(player)) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "Setup access was revoked.");
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= 27) return;
        int slot = event.getRawSlot();
        if (holder.page == Page.GUIDE) {
            switch (slot) {
                case 10 -> openPage(player, Page.ACCESS);
                case 12 -> showCode(player);
                case 14 -> showBrowserSetup(player);
                case 16 -> checkConnection(player, true);
                default -> { }
            }
        } else if (holder.page == Page.ACCESS) {
            switch (slot) {
                case 11 -> selectLocal(player);
                case 13 -> {
                    if (canChangeAccess(player)) openPage(player, Page.PUBLIC_CONFIRM);
                }
                case 15 -> openPage(player, Page.GUIDE);
                default -> { }
            }
        } else if (holder.page == Page.PUBLIC_CONFIRM) {
            switch (slot) {
                case 13 -> requestPublicOrigin(player);
                case 15 -> openPage(player, Page.ACCESS);
                default -> { }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuideHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        PendingOrigin pending = pendingOrigins.remove(playerId);
        if (pending == null) return;
        event.setCancelled(true);
        String entered = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;
            if (!authorized(player) || !canChangeAccess(player)) return;
            if (!Instant.now().isBefore(pending.expiresAt())) {
                player.sendMessage(ChatColor.RED + "The access prompt expired. Open /nab to try again.");
                return;
            }
            if ("cancel".equalsIgnoreCase(entered)) {
                player.sendMessage(ChatColor.GRAY + "Public HTTP setup cancelled.");
                return;
            }
            if (!validPublicOrigin(entered)) {
                player.sendMessage(ChatColor.RED + "Enter exactly http://host:port with no path, query, or credentials. Open /nab to retry.");
                return;
            }
            Access access = accessSupplier.get();
            if (access == null) {
                player.sendMessage(ChatColor.RED + "The panel access service is unavailable. No setting was changed.");
                return;
            }
            Result result = access.applyPublic(entered);
            player.sendMessage((result.success() ? ChatColor.GREEN : ChatColor.RED) + result.message());
            if (result.success()) openPage(player, Page.GUIDE);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingOrigins.remove(event.getPlayer().getUniqueId());
    }

    public static boolean validPublicOrigin(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            URI uri = URI.create(raw);
            return "http".equals(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getPort() >= 1 && uri.getPort() <= 65535
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && raw.equals(uri.toASCIIString());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void openPage(Player player, Page page) {
        GuideHolder holder = new GuideHolder(player.getUniqueId(), page);
        Inventory inventory = Bukkit.createInventory(holder, 27, switch (page) {
            case GUIDE -> "NotABackdoor setup";
            case ACCESS -> "Panel access";
            case PUBLIC_CONFIRM -> "Confirm public HTTP";
        });
        holder.inventory = inventory;
        Access access = accessSupplier.get();
        if (page == Page.GUIDE) {
            inventory.setItem(10, item(Material.COMPASS, "1. Panel access",
                    access == null ? "Service unavailable" : access.accessMode() + ": " + access.advertisedOrigin(),
                    "Click to choose access mode"));
            inventory.setItem(12, item(Material.TRIPWIRE_HOOK, "2. Setup code",
                    auth.isConfigured() ? "Password is already set; reset in console" :
                            plugin.getServer().getOnlineMode() ? "Click to get a private first-run code" : "Console only on offline-mode servers"));
            inventory.setItem(14, item(Material.BOOK, "3. Browser setup",
                    "Open the panel, enter the code,", "then create a password in the browser"));
            inventory.setItem(16, item(Material.REDSTONE_TORCH, "4. Connection check",
                    "Click to check the local listener", "and get a browser verification link",
                    access == null ? "Browser sign-in unavailable"
                            : access.browserLoginReceived(player.getUniqueId())
                            ? "Browser sign-in confirmed" : "Browser sign-in not yet confirmed"));
        } else if (page == Page.ACCESS) {
            inventory.setItem(11, item(Material.IRON_DOOR, "Local access",
                    "127.0.0.1 with SSH forwarding", "Click to select"));
            inventory.setItem(13, item(Material.REDSTONE_BLOCK, "Public HTTP",
                    "Exposes passwords, sessions and commands", "to anyone able to observe the connection.",
                    "Click to review before enabling"));
            inventory.setItem(15, item(Material.ARROW, "Back", "Return to setup guide"));
        } else {
            inventory.setItem(13, item(Material.REDSTONE_BLOCK, "I understand: enable public HTTP",
                    "Passwords, sessions and commands travel", "without transport encryption.",
                    "Click to type an exact http://host:port"));
            inventory.setItem(15, item(Material.ARROW, "Back", "Keep the current access mode"));
        }
        player.openInventory(inventory);
    }

    private void selectLocal(Player player) {
        if (!canChangeAccess(player)) return;
        Access access = accessSupplier.get();
        if (access == null) {
            player.sendMessage(ChatColor.RED + "The panel access service is unavailable. No setting was changed.");
            return;
        }
        Result result = access.applyLocal();
        player.sendMessage((result.success() ? ChatColor.GREEN : ChatColor.RED) + result.message());
        if (result.success()) openPage(player, Page.GUIDE);
    }

    private boolean canChangeAccess(Player player) {
        if (!authorized(player)) {
            player.sendMessage(ChatColor.RED + "Only a permitted server operator can change panel access.");
            return false;
        }
        if (!plugin.getServer().getOnlineMode()) {
            player.sendMessage(ChatColor.YELLOW + "On offline-mode or Velocity backends, change panel access in the server console.");
            return false;
        }
        return true;
    }

    private void requestPublicOrigin(Player player) {
        if (!canChangeAccess(player)) return;
        pendingOrigins.put(player.getUniqueId(), new PendingOrigin(Instant.now().plus(INPUT_LIFETIME)));
        player.closeInventory();
        player.sendMessage(ChatColor.RED + "Public HTTP exposes passwords and sessions in transit.");
        player.sendMessage(ChatColor.YELLOW + "Type the exact http://host:port in chat within 2 minutes, or type cancel.");
        player.sendMessage(ChatColor.GRAY + "The address will not appear in vanilla chat. Do not type a password here.");
    }

    private void showCode(Player player) {
        if (auth.isConfigured()) {
            player.sendMessage(ChatColor.YELLOW + "The panel already has a password. Only the server console can reset it.");
            return;
        }
        if (!plugin.getServer().getOnlineMode()) {
            player.sendMessage(ChatColor.YELLOW + "Run 'nab setup' in the server console, then enter its code in the browser.");
            return;
        }
        AuthService.FirstRunCode code = auth.firstRunCode();
        if (code == null) {
            player.sendMessage(ChatColor.YELLOW + "The panel has already been configured.");
            return;
        }
        player.sendMessage(ChatColor.AQUA + "Private first-run code (expires " + code.expiresAt() + "): ");
        clickable(player, ChatColor.GREEN + "[Copy code]", code.value(), ClickEvent.Action.COPY_TO_CLIPBOARD);
        player.sendMessage(ChatColor.GRAY + code.value());
        player.sendMessage(ChatColor.YELLOW + "Enter the code and your new password in the browser, never in game chat.");
        showBrowserSetup(player);
    }

    private void showBrowserSetup(Player player) {
        Access access = accessSupplier.get();
        if (access == null) {
            player.sendMessage(ChatColor.RED + "The panel access service is unavailable.");
            return;
        }
        String origin = access.advertisedOrigin();
        player.sendMessage(ChatColor.AQUA + "Panel: " + origin + "/");
        clickable(player, ChatColor.AQUA + "[Open panel]", origin + "/", ClickEvent.Action.OPEN_URL);
        if ("local".equalsIgnoreCase(access.accessMode())) {
            player.sendMessage(ChatColor.GRAY + "For a remote server, forward the local port with SSH before opening this address.");
        }
        player.sendMessage(auth.isConfigured()
                ? ChatColor.GRAY + "Sign in with your existing panel password."
                : ChatColor.GRAY + "Enter the setup code and create a password in the browser.");
    }

    private void checkConnection(Player player, boolean includeProof) {
        Access access = accessSupplier.get();
        if (access == null) {
            player.sendMessage(ChatColor.RED + "The panel access service is unavailable.");
            return;
        }
        UUID playerId = player.getUniqueId();
        player.sendMessage(ChatColor.GRAY + "Checking the local panel listener...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Check check;
            try {
                check = access.localHealth();
                if (check == null) check = new Check(false, "The panel did not return a health result.");
            } catch (Exception failure) {
                check = new Check(false, "The local panel check failed: " + failure.getMessage());
            }
            if (!plugin.isEnabled()) return;
            Check result = check;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player current = Bukkit.getPlayer(playerId);
                if (current == null || !current.isOnline() || !authorized(current)) return;
                current.sendMessage((result.reachable() ? ChatColor.GREEN : ChatColor.RED) + result.detail());
                if (!result.reachable() || !includeProof) return;
                String url = access.createBrowserProofUrl(playerId);
                if (url == null || url.isBlank()) {
                    current.sendMessage(ChatColor.YELLOW + "The browser verification link is unavailable.");
                    return;
                }
                current.sendMessage(ChatColor.GRAY + "Open this short-lived link in your browser to verify its route to the panel:");
                clickable(current, ChatColor.AQUA + "[Verify browser connection]", url,
                        ClickEvent.Action.OPEN_URL);
                current.sendMessage(ChatColor.GRAY + "This checks your browser only, not reachability from every network.");
            });
        });
    }

    private static ItemStack item(Material material, String title, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + title);
            meta.setLore(Arrays.stream(lore).map(line -> ChatColor.GRAY + line).toList());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static void clickable(Player player, String label, String value, ClickEvent.Action action) {
        TextComponent component = new TextComponent(label);
        component.setClickEvent(new ClickEvent(action, value));
        player.spigot().sendMessage(component);
    }

    static final class GuideHolder implements InventoryHolder {
        private final UUID playerId;
        private final Page page;
        private Inventory inventory;

        GuideHolder(UUID playerId, Page page) {
            this.playerId = playerId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
