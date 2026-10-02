package com.lian.notabackdoor.panel.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** Password setup, login throttling and short-lived in-memory panel sessions. */
public final class AuthService {
    private static final int PRODUCTION_ITERATIONS = 600_000;
    private static final Duration SETUP_LIFETIME = Duration.ofMinutes(15);
    private static final Duration SESSION_LIFETIME = Duration.ofHours(2);
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final int MAX_FAILURES = 5;

    private final Path credentialsFile;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final int iterations;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();
    private final Map<String, Failure> setupFailures = new ConcurrentHashMap<>();
    private byte[] passwordSalt;
    private byte[] passwordHash;
    private int passwordIterations;
    private String setupCode;
    private Instant setupExpiresAt;
    private Instant lastSuccessfulLoginAt;

    public AuthService(Path dataDirectory) throws IOException {
        this(dataDirectory, Clock.systemUTC(), PRODUCTION_ITERATIONS);
    }

    AuthService(Path dataDirectory, Clock clock, int iterations) throws IOException {
        Files.createDirectories(dataDirectory);
        this.credentialsFile = dataDirectory.resolve("auth.properties");
        this.clock = clock;
        this.iterations = iterations;
        this.passwordIterations = iterations;
        load();
    }

    public synchronized boolean isConfigured() {
        return passwordHash != null;
    }

    /** Called only from a server console command. The code is never included in a URL. */
    public synchronized String issueSetupCode() {
        setupCode = randomToken(12);
        setupExpiresAt = clock.instant().plus(SETUP_LIFETIME);
        setupFailures.clear();
        return setupCode;
    }

    /** Shares an unexpired first-run code across operators without granting password resets. */
    public synchronized FirstRunCode firstRunCode() {
        if (isConfigured()) return null;
        if (setupCode == null || !clock.instant().isBefore(setupExpiresAt)) {
            issueSetupCode();
        }
        return new FirstRunCode(setupCode, setupExpiresAt);
    }

    /** Invalidates a pending code when the panel's network access changes. */
    public synchronized void revokeSetupCode() {
        setupCode = null;
        setupExpiresAt = null;
        setupFailures.clear();
    }

    public synchronized void setPassword(String code, char[] password) throws IOException {
        setPassword(code, password, "unknown");
    }

    public synchronized void setPassword(String code, char[] password, String remoteAddress) throws IOException {
        String source = sourceKey(remoteAddress);
        Instant now = clock.instant();
        Failure failure = setupFailures.get(source);
        if (failure != null && now.isBefore(failure.expiresAt()) && failure.count() >= MAX_FAILURES) {
            throw new SecurityException("Too many setup attempts from this address; try again later");
        }
        if (setupCode == null || !clock.instant().isBefore(setupExpiresAt)
                || !constantTimeEquals(setupCode, code)) {
            int count = failure == null || !now.isBefore(failure.expiresAt()) ? 1 : failure.count() + 1;
            setupFailures.put(source, new Failure(count, now.plus(FAILURE_WINDOW)));
            throw new SecurityException("Invalid or expired setup code");
        }
        if (password == null || password.length < 12 || password.length > 128) {
            throw new IllegalArgumentException("The panel password must contain 12–128 characters");
        }

        byte[] salt = new byte[32];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, iterations);
        save(salt, hash, iterations);
        passwordSalt = salt;
        passwordHash = hash;
        passwordIterations = iterations;
        setupCode = null;
        sessions.clear();
        failures.clear();
        setupFailures.clear();
    }

    public synchronized Login login(char[] password, String remoteAddress) {
        String source = sourceKey(remoteAddress);
        Instant now = clock.instant();
        Failure failure = failures.get(source);
        if (failure != null && now.isBefore(failure.expiresAt()) && failure.count() >= MAX_FAILURES) {
            return null;
        }
        if (passwordHash == null || password == null
                || !MessageDigest.isEqual(passwordHash, derive(password, passwordSalt, passwordIterations))) {
            int count = failure == null || !now.isBefore(failure.expiresAt()) ? 1 : failure.count() + 1;
            failures.put(source, new Failure(count, now.plus(FAILURE_WINDOW)));
            return null;
        }
        failures.remove(source);
        String token = randomToken(32);
        Session session = new Session(randomToken(32), now.plus(SESSION_LIFETIME));
        sessions.put(token, session);
        lastSuccessfulLoginAt = now;
        return new Login(token, session.csrfToken());
    }

    public synchronized Instant lastSuccessfulLoginAt() {
        return lastSuccessfulLoginAt;
    }

    /** Called when changing the listener's network exposure. */
    public synchronized void revokeSessions() {
        sessions.clear();
    }

    public Session authenticate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (!clock.instant().isBefore(session.expiresAt())) {
            sessions.remove(token, session);
            return null;
        }
        return session;
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public boolean hasValidCsrf(Session session, String supplied) {
        return session != null && constantTimeEquals(session.csrfToken(), supplied);
    }

    private void load() throws IOException {
        if (!Files.exists(credentialsFile)) {
            return;
        }
        Properties stored = new Properties();
        try (InputStream input = Files.newInputStream(credentialsFile)) {
            stored.load(input);
            if (!"1".equals(stored.getProperty("format"))) {
                throw new IOException("Unsupported panel credential format");
            }
            int storedIterations = Integer.parseInt(stored.getProperty("iterations"));
            if (storedIterations < 1000 || storedIterations > 5_000_000) {
                throw new IOException("Invalid password work factor");
            }
            passwordIterations = storedIterations;
            passwordSalt = Base64.getDecoder().decode(stored.getProperty("salt"));
            passwordHash = Base64.getDecoder().decode(stored.getProperty("hash"));
            if (passwordSalt.length != 32 || passwordHash.length != 32) {
                throw new IOException("Invalid panel credential lengths");
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Panel credentials are damaged; access remains disabled", invalid);
        }
    }

    private void save(byte[] salt, byte[] hash, int workFactor) throws IOException {
        Properties stored = new Properties();
        stored.setProperty("format", "1");
        stored.setProperty("iterations", Integer.toString(workFactor));
        stored.setProperty("salt", Base64.getEncoder().encodeToString(salt));
        stored.setProperty("hash", Base64.getEncoder().encodeToString(hash));
        Path temporary = Files.createTempFile(credentialsFile.getParent(), ".auth-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                stored.store(output, "NotABackdoor panel password hash; do not publish this file");
            }
            try {
                Files.move(temporary, credentialsFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, credentialsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int workFactor) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, workFactor, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Password hashing is unavailable", unavailable);
        } finally {
            spec.clearPassword();
        }
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String sourceKey(String remoteAddress) {
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    public record FirstRunCode(String value, Instant expiresAt) { }
    public record Session(String csrfToken, Instant expiresAt) { }
    public record Login(String token, String csrfToken) { }
    private record Failure(int count, Instant expiresAt) { }
}
