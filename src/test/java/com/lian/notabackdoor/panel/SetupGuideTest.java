package com.lian.notabackdoor.panel;

import com.lian.notabackdoor.panel.security.AuthService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupGuideTest {
    @Test
    void publicOriginMustBeOneExactPlainHttpAuthority() {
        assertTrue(SetupGuide.validPublicOrigin("http://panel.example.org:8127"));
        assertTrue(SetupGuide.validPublicOrigin("http://192.168.0.155:27021"));
        assertFalse(SetupGuide.validPublicOrigin("https://panel.example.org:8127"));
        assertFalse(SetupGuide.validPublicOrigin("http://panel.example.org"));
        assertFalse(SetupGuide.validPublicOrigin("http://user:secret@panel.example.org:8127"));
        assertFalse(SetupGuide.validPublicOrigin("http://panel.example.org:8127/"));
        assertFalse(SetupGuide.validPublicOrigin("http://panel.example.org:8127/?setup=code"));
        assertFalse(SetupGuide.validPublicOrigin("http://panel.example.org:8127#fragment"));
        assertFalse(SetupGuide.validPublicOrigin("http://panel.example.org:0"));
        assertFalse(SetupGuide.validPublicOrigin("http://panel.example.org:65536"));
        assertFalse(SetupGuide.validPublicOrigin(""));
    }

    @Test
    void guideRequiresBothOperatorAndPermission() {
        assertTrue(SetupGuide.authorized(player(true, true)));
        assertFalse(SetupGuide.authorized(player(false, true)));
        assertFalse(SetupGuide.authorized(player(true, false)));
    }

    @Test
    void everyClickAndDragThroughTheGuideIsCancelled() {
        UUID id = UUID.randomUUID();
        SetupGuide.GuideHolder holder = new SetupGuide.GuideHolder(id, SetupGuide.Page.GUIDE);
        Inventory top = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(top.getHolder()).thenReturn(holder);
        when(view.getTopInventory()).thenReturn(top);
        Player operator = mock(Player.class);
        when(operator.getUniqueId()).thenReturn(id);
        when(operator.isOp()).thenReturn(true);
        when(operator.hasPermission("notabackdoor.admin")).thenReturn(true);
        SetupGuide guide = new SetupGuide(null, null, () -> null);

        // A bottom-inventory click can be a shift-click, hotbar swap or double click.
        // Cancelling every click while this view is open closes all transfer paths.
        for (int rawSlot : new int[]{0, 27, 35}) {
            InventoryClickEvent click = mock(InventoryClickEvent.class);
            when(click.getView()).thenReturn(view);
            when(click.getWhoClicked()).thenReturn(operator);
            when(click.getRawSlot()).thenReturn(rawSlot);
            guide.onClick(click);
            verify(click).setCancelled(true);
        }
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        guide.onDrag(drag);
        verify(drag).setCancelled(true);
    }

    @Test
    void offlineOperatorsCannotIssueAFirstRunCodeButOnlineOperatorsCan() {
        UUID id = UUID.randomUUID();
        SetupGuide.GuideHolder holder = new SetupGuide.GuideHolder(id, SetupGuide.Page.GUIDE);
        Inventory top = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(top.getHolder()).thenReturn(holder);
        when(view.getTopInventory()).thenReturn(top);
        Player operator = mock(Player.class);
        when(operator.getUniqueId()).thenReturn(id);
        when(operator.isOp()).thenReturn(true);
        when(operator.hasPermission("notabackdoor.admin")).thenReturn(true);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getView()).thenReturn(view);
        when(click.getWhoClicked()).thenReturn(operator);
        when(click.getRawSlot()).thenReturn(12);
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        AuthService offlineAuth = mock(AuthService.class);
        new SetupGuide(plugin, offlineAuth, () -> null).onClick(click);
        verify(offlineAuth, never()).firstRunCode();
        when(server.getOnlineMode()).thenReturn(true);
        AuthService onlineAuth = mock(AuthService.class);
        new SetupGuide(plugin, onlineAuth, () -> null).onClick(click);
        verify(onlineAuth).firstRunCode();
    }

    private static Player player(boolean operator, boolean permission) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOp" -> operator;
                    case "hasPermission" -> permission;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
