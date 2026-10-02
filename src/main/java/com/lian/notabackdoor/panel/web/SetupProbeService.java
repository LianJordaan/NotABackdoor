package com.lian.notabackdoor.panel.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

/** Short-lived, non-authenticating proof that an operator's browser reached the panel. */
public final class SetupProbeService {
    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Map<UUID, Probe> probes = new HashMap<>();
    private final Set<UUID> completed = new HashSet<>();

    public SetupProbeService() {
        this(Clock.systemUTC());
    }

    SetupProbeService(Clock clock) {
        this.clock = clock;
    }

    public synchronized String issue(UUID operator) {
        if (operator == null) throw new IllegalArgumentException("Operator identity is required");
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        probes.put(operator, new Probe(nonce, clock.instant().plus(LIFETIME)));
        return nonce;
    }

    public synchronized boolean markReached(String nonce) {
        Probe probe = find(nonce);
        if (probe == null) return false;
        probe.reached = true;
        return true;
    }

    public synchronized boolean markLoggedIn(String nonce) {
        UUID owner = owner(nonce);
        if (owner == null) return false;
        Probe probe = probes.get(owner);
        if (!probe.reached) return false;
        probe.loggedIn = true;
        completed.add(owner);
        return true;
    }

    public synchronized boolean reached(UUID operator) {
        if (completed.contains(operator)) return true;
        Probe probe = current(operator);
        return probe != null && probe.reached;
    }

    public synchronized boolean loggedIn(UUID operator) {
        return completed.contains(operator);
    }

    public synchronized void clear() {
        probes.clear();
        completed.clear();
    }

    private Probe find(String nonce) {
        UUID owner = owner(nonce);
        return owner == null ? null : probes.get(owner);
    }

    private UUID owner(String nonce) {
        if (nonce == null || nonce.length() != 32) return null;
        probes.entrySet().removeIf(entry -> !clock.instant().isBefore(entry.getValue().expiresAt));
        for (Map.Entry<UUID, Probe> entry : probes.entrySet()) {
            if (entry.getValue().nonce.equals(nonce)) return entry.getKey();
        }
        return null;
    }

    private Probe current(UUID operator) {
        Probe probe = probes.get(operator);
        if (probe != null && !clock.instant().isBefore(probe.expiresAt)) {
            probes.remove(operator);
            return null;
        }
        return probe;
    }

    private static final class Probe {
        final String nonce;
        final Instant expiresAt;
        boolean reached;
        boolean loggedIn;

        Probe(String nonce, Instant expiresAt) {
            this.nonce = nonce;
            this.expiresAt = expiresAt;
        }
    }
}
