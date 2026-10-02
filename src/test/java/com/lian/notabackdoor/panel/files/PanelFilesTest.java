package com.lian.notabackdoor.panel.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelFilesTest {
    @TempDir Path directory;

    @BeforeEach
    void requiresRaceSafeDirectoryHandles() throws IOException {
        try (var opened = Files.newDirectoryStream(directory)) {
            Assumptions.assumeTrue(opened instanceof SecureDirectoryStream<?>,
                    "The filesystem must provide SecureDirectoryStream");
        }
    }

    @Test
    void textWritesRequireTheVersionActuallyOpened() throws IOException {
        PanelFiles files = new PanelFiles(directory);
        files.create("config.yml", false);
        PanelFiles.TextFile opened = files.readText("config.yml");
        files.writeText("config.yml", "first: true\n", opened.sha256());
        assertThrows(PanelFiles.ConflictException.class,
                () -> files.writeText("config.yml", "stale: true\n", opened.sha256()));
        assertEquals("first: true\n", files.readText("config.yml").content());
    }

    @Test
    void createUploadEditAndDownloadRoundTrip() throws IOException {
        try (PanelFiles files = new PanelFiles(directory)) {
            files.create("plugins", true);
            files.create("plugins/config.yml", false);
            PanelFiles.TextFile opened = files.readText("plugins/config.yml");
            files.writeText("plugins/config.yml", "enabled: true\n", opened.sha256());
            files.upload("plugins/mod.jar", new ByteArrayInputStream(new byte[]{1, 2, 3}));
            try (SecureFileRoot.OpenedFile downloaded = files.download("plugins/mod.jar")) {
                assertEquals(3, downloaded.size());
                assertEquals(3, downloaded.input().readAllBytes().length);
            }
            assertEquals("enabled: true\n", files.readText("plugins/config.yml").content());
        }
    }

    @Test
    void refusesAnExistingSymlinkAtEveryDepth() throws IOException {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("note.txt"), "outside-secret");
        Files.createSymbolicLink(root.resolve("linked"), outside);
        Files.createSymbolicLink(root.resolve("direct.txt"), outside.resolve("note.txt"));
        try (PanelFiles files = new PanelFiles(root)) {
            assertThrows(IOException.class, () -> files.readText("linked/note.txt"));
            assertThrows(IllegalArgumentException.class, () -> files.download("direct.txt"));
        }
    }

    @Test
    void createMoveUploadAndRecursiveDeleteRemainBelowTheRoot() throws IOException {
        PanelFiles files = new PanelFiles(directory);
        files.create("plugins", true);
        files.upload("plugins/example.jar", new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertEquals(3, files.list("plugins").get(0).size());
        files.move("plugins/example.jar", "plugins/renamed.jar");
        assertTrue(Files.exists(directory.resolve("plugins/renamed.jar")));
        assertThrows(IllegalArgumentException.class, () -> files.delete("../outside"));
        files.delete("plugins");
        assertFalse(Files.exists(directory.resolve("plugins")));
    }

    @Test
    void rejectsArchiveTraversalWithoutLeavingAnExtractionDirectory() throws IOException {
        PanelFiles files = new PanelFiles(directory);
        Path archive = directory.resolve("escape.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../outside.txt"));
            zip.write("bad".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThrows(IllegalArgumentException.class, () -> files.unzip("escape.zip", "result"));
        assertFalse(Files.exists(directory.resolve("result")));
        assertFalse(Files.exists(directory.getParent().resolve("outside.txt")));
    }

    @Test
    void createsAnArchiveAndExtractsIntoANewDirectory() throws IOException {
        PanelFiles files = new PanelFiles(directory);
        files.create("plugins", true);
        Files.writeString(directory.resolve("plugins/config.yml"), "enabled: true\n");
        files.zip("plugins", "archive.zip");
        files.unzip("archive.zip", "restored");
        assertEquals("enabled: true\n", Files.readString(directory.resolve("restored/plugins/config.yml")));
    }

    @Test
    void refusesArchivesWhoseSourceExceedsTheByteLimit() throws IOException {
        PanelFiles files = new PanelFiles(directory);
        Path sparse = directory.resolve("large.bin");
        try (RandomAccessFile file = new RandomAccessFile(sparse.toFile(), "rw")) {
            file.setLength(1024L * 1024 * 1024 + 1);
        }
        assertThrows(IOException.class, () -> files.zip("large.bin", "large.zip"));
        assertFalse(Files.exists(directory.resolve("large.zip")));
    }

    @Test
    void symlinkSwapCannotReadOrWriteOutsideTheRoot() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path inside = Files.createDirectory(root.resolve("sub"));
        Files.writeString(inside.resolve("note.txt"), "inside-file");
        Files.writeString(outside.resolve("note.txt"), "outside-secret");
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger flips = new AtomicInteger();
        try (PanelFiles files = new PanelFiles(root)) {
            Thread flipper = new Thread(() -> {
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
            flipper.start();
            try {
                for (int attempt = 0; attempt < 50_000; attempt++) {
                    try {
                        assertEquals("inside-file", files.readText("sub/note.txt").content());
                    } catch (IOException | IllegalArgumentException | SecurityException concurrentChange) {
                        // A concurrently renamed path may disappear; it must never resolve outside.
                    }
                    if (attempt % 100 == 0) {
                        try { files.upload("sub/new-" + attempt + ".txt",
                                new ByteArrayInputStream("inside-only".getBytes(StandardCharsets.UTF_8))); }
                        catch (IOException | IllegalArgumentException | SecurityException concurrentChange) { }
                    }
                }
            } finally {
                stop.set(true);
                flipper.join();
            }
        } finally {
            assertTrue(flips.get() > 0, "The competing directory replacement must actually run");
            assertEquals("outside-secret", Files.readString(outside.resolve("note.txt")));
            try (var entries = Files.list(outside)) { assertEquals(1, entries.count()); }
        }
    }
}
