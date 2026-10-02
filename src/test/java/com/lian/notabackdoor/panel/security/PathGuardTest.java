package com.lian.notabackdoor.panel.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathGuardTest {
    @TempDir Path directory;

    @Test
    void validatesRelativeNamesWithoutReturningPathsForLaterReopening() throws IOException {
        PathGuard guard = new PathGuard(directory);
        Path nested = Files.createDirectories(directory.resolve("plugins"));
        assertEquals(List.of("plugins", "config.yml"), PathGuard.segments("plugins/config.yml"));
        assertEquals("plugins/config.yml", guard.relative(nested.resolve("config.yml")));
        assertEquals(List.of(), PathGuard.segments(""));
    }

    @Test
    void rejectsTraversalAndPlatformSpecificAbsolutePaths() throws IOException {
        for (String bad : new String[]{"../secret", "plugins/../secret", "./file", "/etc/passwd",
                "C:\\secret", "C:secret", "plugins\\config.yml", "two//parts", "x/",
                "CON", "nul.txt", "plugins/LPT1.log", "folder./file", "trailing /file"}) {
            assertThrows(IllegalArgumentException.class, () -> PathGuard.segments(bad), bad);
        }
    }
}
