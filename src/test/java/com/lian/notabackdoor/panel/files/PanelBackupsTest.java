package com.lian.notabackdoor.panel.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelBackupsTest {
    @TempDir Path root;

    @Test
    void archiveContainsServerFilesButNotPreviousBackupsOrItsOwnTemporaryFile() throws IOException {
        Files.createDirectories(root.resolve("world"));
        Files.writeString(root.resolve("world/level.dat"), "fixture");
        Files.writeString(root.resolve("world/session.lock"), "ephemeral");
        PanelBackups backups = new PanelBackups(root, root.resolve("plugins/NotABackdoor/backups"));
        Files.writeString(root.resolve("plugins/NotABackdoor/backups/backup-20200101-000000.zip"), "old");
        PanelBackups.Backup made = backups.create();
        try (ZipFile zip = new ZipFile(backups.download(made.name()).toFile())) {
            assertTrue(zip.stream().anyMatch(entry -> entry.getName().equals("world/level.dat")));
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().contains("backups/")));
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().endsWith("session.lock")));
        }
        assertThrows(IllegalArgumentException.class, () -> backups.download("../outside.zip"));
        assertEquals(2, backups.list().size());
        backups.delete(made.name());
        assertEquals(1, backups.list().size());
    }
}
