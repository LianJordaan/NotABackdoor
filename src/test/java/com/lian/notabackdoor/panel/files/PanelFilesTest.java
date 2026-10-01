package com.lian.notabackdoor.panel.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelFilesTest {
    @TempDir Path directory;

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
}
