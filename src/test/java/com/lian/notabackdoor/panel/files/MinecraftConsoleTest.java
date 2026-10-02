package com.lian.notabackdoor.panel.files;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftConsoleTest {
    @TempDir Path root;

    @Test
    void tailsRealLogWithoutDuplicatingLinesAndSurvivesRotation() throws Exception {
        assumeSecureDirectories();
        Path logs = Files.createDirectory(root.resolve("logs"));
        Path latest = logs.resolve("latest.log");
        Files.writeString(latest, "[00:00:01] [Server thread/INFO]: Starting minecraft server\n"
                + "[00:00:02] [Server thread/INFO]: Done\n");
        try (MinecraftConsole console = new MinecraftConsole(root)) {
            MinecraftConsole.Batch first = console.read("");
            assertTrue(first.available());
            assertTrue(first.reset());
            assertEquals(2, first.lines().size());
            assertTrue(first.lines().get(0).contains("Starting minecraft server"));
            assertTrue(console.read(first.cursor()).lines().isEmpty());

            Files.writeString(latest, "[00:00:03] [Server thread/INFO]: Partial",
                    StandardOpenOption.APPEND);
            assertTrue(console.read(first.cursor()).lines().isEmpty());
            Files.writeString(latest, " command\n", StandardOpenOption.APPEND);
            MinecraftConsole.Batch appended = console.read(first.cursor());
            assertEquals(1, appended.lines().size());
            assertTrue(appended.lines().get(0).contains("Partial command"));
            assertFalse(appended.reset());

            Files.move(latest, logs.resolve("old.log"));
            Files.writeString(latest, "[00:00:04] [Server thread/INFO]: New boot\n");
            MinecraftConsole.Batch rotated = console.read(appended.cursor());
            assertTrue(rotated.reset());
            assertEquals(1, rotated.lines().size());
            assertTrue(rotated.lines().get(0).contains("New boot"));
        }
    }

    @Test
    void refusesSymlinkedLogDirectoryAndLogFile() throws Exception {
        assumeSecureDirectories();
        Path outside = Files.createTempFile("nab-outside-log", ".txt");
        Files.writeString(outside, "secret outside the server root\n");
        try {
            Path logs = Files.createDirectory(root.resolve("logs"));
            try {
                Files.createSymbolicLink(logs.resolve("latest.log"), outside);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
                Assumptions.abort("Symlinks are unavailable");
            }
            try (MinecraftConsole console = new MinecraftConsole(root)) {
                assertThrows(SecurityException.class, () -> console.read(null));
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void neverFollowsAReplacedLogsDirectory() throws Exception {
        assumeSecureDirectories();
        Path outside = Files.createTempDirectory("nab-outside-logs");
        Files.writeString(outside.resolve("latest.log"), "outside console\n");
        try {
            try {
                Files.createSymbolicLink(root.resolve("logs"), outside);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
                Assumptions.abort("Symlinks are unavailable");
            }
            try (MinecraftConsole console = new MinecraftConsole(root)) {
                assertThrows(java.io.IOException.class, () -> console.read(null));
            }
        } finally {
            Files.deleteIfExists(outside.resolve("latest.log"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void unavailableUntilMinecraftCreatesItsLog() throws Exception {
        assumeSecureDirectories();
        try (MinecraftConsole console = new MinecraftConsole(root)) {
            MinecraftConsole.Batch result = console.read(null);
            assertFalse(result.available());
            assertTrue(result.lines().isEmpty());
        }
    }

    @Test
    void boundsLargeStartupLogToMostRecentLines() throws Exception {
        assumeSecureDirectories();
        Path logs = Files.createDirectory(root.resolve("logs"));
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 12_000; index++) {
            content.append("[00:00:00] [Server thread/INFO]: entry ").append(index).append('\n');
        }
        Files.writeString(logs.resolve("latest.log"), content);
        try (MinecraftConsole console = new MinecraftConsole(root)) {
            MinecraftConsole.Batch tail = console.read(null);
            assertTrue(tail.truncated());
            assertEquals(800, tail.lines().size());
            assertTrue(tail.lines().get(799).contains("entry 11999"));
            assertTrue(console.read(tail.cursor()).lines().isEmpty());
        }
    }

    private void assumeSecureDirectories() throws Exception {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            Assumptions.assumeTrue(stream instanceof SecureDirectoryStream<?>);
        }
    }
}
