package com.lian.notabackdoor.panel.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelBackupsTest {
    @TempDir Path root;

    @BeforeEach
    void requiresRaceSafeDirectoryHandles() throws IOException {
        try (var opened = Files.newDirectoryStream(root)) {
            Assumptions.assumeTrue(opened instanceof SecureDirectoryStream<?>,
                    "The filesystem must provide SecureDirectoryStream");
        }
    }

    @Test
    void archiveContainsServerFilesButNotPreviousBackupsOrItsOwnTemporaryFile() throws IOException {
        Files.createDirectories(root.resolve("world"));
        Files.writeString(root.resolve("world/level.dat"), "fixture");
        Files.writeString(root.resolve("world/session.lock"), "ephemeral");
        try (PanelBackups backups = new PanelBackups(root, root.resolve("plugins/NotABackdoor/backups"))) {
            Files.writeString(root.resolve("plugins/NotABackdoor/backups/backup-20200101-000000.zip"), "old");
            PanelBackups.Backup made = backups.create();
            Set<String> names = new HashSet<>();
            try (SecureFileRoot.OpenedFile opened = backups.download(made.name());
                 ZipInputStream zip = new ZipInputStream(opened.input())) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
            }
            assertTrue(names.contains("world/level.dat"));
            assertFalse(names.stream().anyMatch(name -> name.contains("backups/")));
            assertFalse(names.stream().anyMatch(name -> name.endsWith("session.lock")));
            assertThrows(IllegalArgumentException.class, () -> backups.download("../outside.zip"));
            assertEquals(2, backups.list().size());
            backups.delete(made.name());
            assertEquals(1, backups.list().size());
        }
    }
}
