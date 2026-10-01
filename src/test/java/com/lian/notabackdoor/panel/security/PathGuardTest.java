package com.lian.notabackdoor.panel.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathGuardTest {
    @TempDir Path directory;

    @Test
    void resolvesOnlyPathsBelowTheChosenRoot() throws IOException {
        PathGuard guard = new PathGuard(directory);
        Path nested = Files.createDirectories(directory.resolve("plugins"));
        assertEquals(nested.resolve("config.yml"), guard.resolveChild("plugins/config.yml"));
        assertEquals("plugins/config.yml", guard.relative(nested.resolve("config.yml")));
        assertEquals(guard.root(), guard.resolve(""));
    }

    @Test
    void rejectsTraversalAndPlatformSpecificAbsolutePaths() throws IOException {
        PathGuard guard = new PathGuard(directory);
        for (String bad : new String[]{"../secret", "plugins/../secret", "./file", "/etc/passwd",
                "C:\\secret", "C:secret", "plugins\\config.yml", "two//parts", "x/",
                "CON", "nul.txt", "plugins/LPT1.log", "folder./file", "trailing /file"}) {
            assertThrows(IllegalArgumentException.class, () -> guard.resolve(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> guard.resolveChild(""));
    }

    @Test
    void rejectsExistingSymlinksWhenThePlatformAllowsThem() throws IOException {
        PathGuard guard = new PathGuard(directory);
        Path outside = Files.createTempDirectory("nab-outside-");
        try {
            Path link = directory.resolve("escape");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
                return;
            }
            assertThrows(SecurityException.class, () -> guard.resolve("escape/secret.txt"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}
