package com.lian.notabackdoor.panel.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {
    @TempDir Path directory;

    @Test
    void setupStoresOnlyAHashAndSurvivesRestart() throws IOException {
        MutableClock clock = new MutableClock();
        AuthService service = new AuthService(directory, clock, 1_000);
        assertFalse(service.isConfigured());
        String code = service.issueSetupCode();
        assertThrows(SecurityException.class, () -> service.setPassword("wrong", "a long test password".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> service.setPassword(code, "short".toCharArray()));
        service.setPassword(code, "a long test password".toCharArray());
        assertTrue(service.isConfigured());
        assertFalse(Files.readString(directory.resolve("auth.properties")).contains("a long test password"));
        assertThrows(SecurityException.class, () -> service.setPassword(code, "another long password".toCharArray()));

        AuthService restarted = new AuthService(directory, clock, 2_000);
        assertTrue(restarted.isConfigured());
        assertNull(restarted.login("wrong password".toCharArray(), "127.0.0.1"));
        assertNotNull(restarted.login("a long test password".toCharArray(), "127.0.0.1"));
    }

    @Test
    void sessionsNeedCsrfExpireAndAreRevokedOnPasswordChange() throws IOException {
        MutableClock clock = new MutableClock();
        AuthService service = new AuthService(directory, clock, 1_000);
        service.setPassword(service.issueSetupCode(), "a long test password".toCharArray());
        AuthService.Login login = service.login("a long test password".toCharArray(), "127.0.0.1");
        assertNotNull(login);
        AuthService.Session session = service.authenticate(login.token());
        assertNotNull(session);
        assertFalse(service.hasValidCsrf(session, "wrong"));
        assertTrue(service.hasValidCsrf(session, login.csrfToken()));

        service.setPassword(service.issueSetupCode(), "another long password".toCharArray());
        assertNull(service.authenticate(login.token()));
        AuthService.Login replacement = service.login("another long password".toCharArray(), "127.0.0.1");
        assertNotNull(replacement);
        clock.advance(Duration.ofHours(3));
        assertNull(service.authenticate(replacement.token()));
    }

    @Test
    void limitsRepeatedLoginFailuresAndExpiresConsoleSetupCodes() throws IOException {
        MutableClock clock = new MutableClock();
        AuthService service = new AuthService(directory, clock, 1_000);
        String expired = service.issueSetupCode();
        clock.advance(Duration.ofMinutes(16));
        assertThrows(SecurityException.class, () -> service.setPassword(expired, "a long test password".toCharArray()));
        service.setPassword(service.issueSetupCode(), "a long test password".toCharArray());
        for (int i = 0; i < 5; i++) {
            assertNull(service.login("wrong password".toCharArray(), "192.0.2.1"));
        }
        assertNull(service.login("a long test password".toCharArray(), "192.0.2.1"));
        assertNotNull(service.login("a long test password".toCharArray(), "192.0.2.2"));
        clock.advance(Duration.ofMinutes(16));
        assertNotNull(service.login("a long test password".toCharArray(), "192.0.2.1"));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
