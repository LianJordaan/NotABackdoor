package com.lian.notabackdoor.panel.web;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupProbeServiceTest {
    @Test
    void browserCheckIsBoundToItsOperatorAndRequiresAReachedPageBeforeLogin() {
        MutableClock clock = new MutableClock();
        SetupProbeService probes = new SetupProbeService(clock);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String firstLink = probes.issue(first);
        String secondLink = probes.issue(second);
        assertNotEquals(firstLink, secondLink);
        assertFalse(probes.markLoggedIn(firstLink));
        assertTrue(probes.markReached(firstLink));
        assertTrue(probes.reached(first));
        assertFalse(probes.reached(second));
        assertTrue(probes.markLoggedIn(firstLink));
        assertTrue(probes.loggedIn(first));
        assertFalse(probes.loggedIn(second));
        assertTrue(probes.markReached(secondLink));
        assertTrue(probes.markLoggedIn(secondLink));
    }

    @Test
    void linksExpireButCompletedChecksRemainUntilListenerAccessChanges() {
        MutableClock clock = new MutableClock();
        SetupProbeService probes = new SetupProbeService(clock);
        UUID completed = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        String completedLink = probes.issue(completed);
        String pendingLink = probes.issue(pending);
        assertTrue(probes.markReached(completedLink));
        assertTrue(probes.markLoggedIn(completedLink));
        clock.advance(Duration.ofMinutes(11));
        assertFalse(probes.markReached(pendingLink));
        assertFalse(probes.reached(pending));
        assertTrue(probes.reached(completed));
        assertTrue(probes.loggedIn(completed));
        probes.clear();
        assertFalse(probes.loggedIn(completed));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-02T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
