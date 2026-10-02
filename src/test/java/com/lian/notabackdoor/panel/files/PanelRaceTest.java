package com.lian.notabackdoor.panel.files;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A local writer swaps a checked directory for an outside symlink during file operations. */
class PanelRaceTest {
    @TempDir Path temporary;

    @BeforeEach
    void requiresRaceSafeDirectoryHandles() throws IOException {
        try (var opened = Files.newDirectoryStream(temporary)) {
            Assumptions.assumeTrue(opened instanceof SecureDirectoryStream<?>,
                    "The filesystem must provide SecureDirectoryStream");
        }
    }

    @Test
    void downloadNeverReturnsOutsideBytes() throws Exception {
        Fixture fixture = new Fixture(temporary);
        try (PanelFiles files = new PanelFiles(fixture.root);
             Flipper flipper = new Flipper(fixture.root, fixture.outside)) {
            for (int attempt = 0; attempt < 20_000; attempt++) {
                try (SecureFileRoot.OpenedFile opened = files.download("sub/note.txt")) {
                    assertEquals("inside-file", new String(opened.input().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException | IllegalArgumentException | SecurityException concurrentChange) {
                    // A replaced path may disappear or become a link; outside bytes must not be returned.
                }
            }
            assertTrue(flipper.flips.get() > 0);
        }
        assertEquals("outside-secret", Files.readString(fixture.outside.resolve("note.txt")));
    }

    @Test
    void zipNeverIncludesOutsideBytes() throws Exception {
        Fixture fixture = new Fixture(temporary);
        int completed = 0;
        try (PanelFiles files = new PanelFiles(fixture.root);
             Flipper flipper = new Flipper(fixture.root, fixture.outside)) {
            // Concurrent prevalidation can legitimately abort many attempts while the
            // directory is between moves; keep trying until one archive completes.
            for (int attempt = 0; attempt < 1_000 && (attempt < 100 || completed == 0); attempt++) {
                String archive = "archive-" + attempt + ".zip";
                try {
                    files.zip("sub", archive);
                    completed++;
                    try (SecureFileRoot.OpenedFile opened = files.download(archive);
                         ZipInputStream zip = new ZipInputStream(opened.input())) {
                        ZipEntry entry;
                        while ((entry = zip.getNextEntry()) != null) {
                            assertFalse(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("outside-secret"),
                                    "Outside bytes entered " + entry.getName());
                        }
                    }
                } catch (IOException | IllegalArgumentException | SecurityException concurrentChange) {
                    // The source can vanish mid-operation; it must not follow the replacement link.
                }
            }
            assertTrue(flipper.flips.get() > 0);
        }
        assertTrue(completed > 0, "At least one archive must complete during the race");
        assertEquals("outside-secret", Files.readString(fixture.outside.resolve("note.txt")));
    }

    @Test
    void backupNeverIncludesOutsideBytes() throws Exception {
        Fixture fixture = new Fixture(temporary);
        Clock clock = new Clock() {
            private final AtomicLong seconds = new AtomicLong(1_700_000_000L);
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochSecond(seconds.getAndIncrement()); }
        };
        int completed = 0;
        try (PanelBackups backups = new PanelBackups(fixture.root,
                fixture.root.resolve("plugins/NotABackdoor/backups"), clock);
             Flipper flipper = new Flipper(fixture.root, fixture.outside)) {
            for (int attempt = 0; attempt < 30; attempt++) {
                try {
                    PanelBackups.Backup made = backups.create();
                    completed++;
                    try (SecureFileRoot.OpenedFile opened = backups.download(made.name());
                         ZipInputStream zip = new ZipInputStream(opened.input())) {
                        ZipEntry entry;
                        while ((entry = zip.getNextEntry()) != null) {
                            assertFalse(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("outside-secret"),
                                    "Outside bytes entered " + entry.getName());
                        }
                    }
                } catch (IOException | IllegalArgumentException | SecurityException concurrentChange) {
                    // Concurrent moves can abort a backup, but cannot redirect it outside the root.
                }
            }
            assertTrue(flipper.flips.get() > 0);
        }
        assertTrue(completed > 0, "At least one backup must complete during the race");
        assertEquals("outside-secret", Files.readString(fixture.outside.resolve("note.txt")));
    }

    private static final class Fixture {
        final Path root;
        final Path outside;
        Fixture(Path parent) throws IOException {
            root = Files.createDirectory(parent.resolve("root"));
            outside = Files.createDirectory(parent.resolve("outside"));
            Path inside = Files.createDirectory(root.resolve("sub"));
            Files.writeString(inside.resolve("note.txt"), "inside-file");
            Files.writeString(outside.resolve("note.txt"), "outside-secret");
        }
    }

    private static final class Flipper implements AutoCloseable {
        final AtomicInteger flips = new AtomicInteger();
        private final AtomicBoolean stop = new AtomicBoolean();
        private final Thread thread;
        Flipper(Path root, Path outside) {
            thread = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        Files.move(root.resolve("sub"), root.resolve("sub-good"));
                        Files.createSymbolicLink(root.resolve("sub"), outside);
                        flips.incrementAndGet();
                        Thread.yield();
                        Files.delete(root.resolve("sub"));
                        Files.move(root.resolve("sub-good"), root.resolve("sub"));
                    } catch (IOException | SecurityException ignored) {
                        try {
                            if (Files.isSymbolicLink(root.resolve("sub"))) Files.deleteIfExists(root.resolve("sub"));
                            if (Files.exists(root.resolve("sub-good")) && !Files.exists(root.resolve("sub"))) {
                                Files.move(root.resolve("sub-good"), root.resolve("sub"));
                            }
                        } catch (IOException ignoredAgain) { }
                    }
                }
            }, "panel-symlink-swap");
            thread.start();
        }
        @Override public void close() throws InterruptedException { stop.set(true); thread.join(); }
    }
}
