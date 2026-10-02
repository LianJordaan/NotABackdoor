package com.lian.notabackdoor.panel.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class PanelAccessTest {
    private final InetAddress loopback = address("127.0.0.1");
    private final InetAddress remote = address("203.0.113.8");

    @Test
    void localModeAllowsOnlyLoopbackNamesFromLoopbackPeers() {
        PanelAccess access = PanelAccess.local(8127);
        assertEquals(PanelAccess.Mode.LOCAL, access.mode());
        assertEquals("127.0.0.1", access.bindAddress());
        assertTrue(access.allowsHost("127.0.0.1:8127", loopback));
        assertTrue(access.allowsHost("localhost:8127", loopback));
        assertFalse(access.allowsHost("localhost:8127", remote));
        assertFalse(access.allowsHost("evil.example:8127", loopback));
        assertFalse(access.allowsHost("localhost:8127.evil.example", loopback));
        assertFalse(access.allowsHost("localhost:8128", loopback));
        assertTrue(access.allowsOrigin(null, loopback, false));
        assertTrue(access.allowsOrigin("http://localhost:8127", loopback, false));
        assertFalse(access.allowsOrigin("http://evil.example:8127", loopback, false));
    }

    @Test
    void publicModeAllowsOnlyConfiguredOriginAndLocalLoopback() {
        PanelAccess access = PanelAccess.publicHttp("http://panel.example.test:8127", 8127);
        assertEquals(PanelAccess.Mode.PUBLIC_HTTP, access.mode());
        assertEquals("0.0.0.0", access.bindAddress());
        assertEquals("http://panel.example.test:8127", access.advertisedOrigin());
        assertTrue(access.allowsHost("panel.example.test:8127", remote));
        assertTrue(access.allowsOrigin("http://panel.example.test:8127", remote, true));
        assertFalse(access.allowsOrigin(null, remote, true));
        assertFalse(access.allowsOrigin("http://panel.example.test:8127/", remote, true));
        assertFalse(access.allowsHost("other.example.test:8127", remote));
        assertFalse(access.allowsOrigin("http://other.example.test:8127", remote, false));
        assertFalse(access.allowsHost("127.0.0.1:8127", remote));
        assertFalse(access.allowsOrigin("http://127.0.0.1:8127", remote, true));
        assertTrue(access.allowsHost("localhost:8127", loopback));
        assertTrue(access.allowsOrigin("http://localhost:8127", loopback, true));
    }

    @Test
    void rejectsAmbiguousOrUnusableAdvertisedOrigins() {
        String[] invalid = {
                "https://panel.example.test:8127", "http://panel.example.test",
                "http://panel.example.test:8128", "http://panel.example.test:8127/",
                "http://panel.example.test:8127/path", "http://panel.example.test:8127?x=1",
                "http://panel.example.test:8127#fragment", "http://user@panel.example.test:8127",
                "http://0.0.0.0:8127", "http://127.0.0.1:8127", "http://127.0.0.2:8127",
                "http://localhost:8127", "http://[::1]:8127", "http://panel.example.test:8127 ",
                "http://panel_example.test:8127", "http://panel.example.test:8127\\@evil.test",
                "http://Panel.Example.Test:8127", "http://panel.example.test:08127"
        };
        for (String origin : invalid) {
            assertThrows(IllegalArgumentException.class, () -> PanelAccess.publicHttp(origin, 8127), origin);
        }
        assertThrows(IllegalArgumentException.class, () -> PanelAccess.local(80));
        assertThrows(IllegalArgumentException.class, () -> PanelAccess.publicHttp("http://host:65536", 65536));
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
