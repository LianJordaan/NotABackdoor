package com.lian.notabackdoor.panel.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureProviderTest {
    @TempDir Path root;

    @Test
    void providerWithoutRaceSafeDirectoryHandlesFailsClosed() throws IOException {
        boolean supported;
        try (var opened = Files.newDirectoryStream(root)) {
            supported = opened instanceof SecureDirectoryStream<?>;
        }
        if (supported) {
            try (PanelFiles ignored = new PanelFiles(root)) {
                // The provider supports the required handle operations.
            }
        } else {
            IOException failure = assertThrows(IOException.class, () -> new PanelFiles(root));
            assertTrue(failure.getMessage().contains("race-safe directory handles"));
        }
    }
}
