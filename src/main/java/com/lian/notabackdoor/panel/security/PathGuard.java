package com.lian.notabackdoor.panel.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Validates relative path syntax without exposing a check-then-reopen path API. */
public final class PathGuard {
    private final Path root;

    public PathGuard(Path root) throws IOException {
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root)) {
            throw new IllegalArgumentException("The file root must be a directory");
        }
    }

    public Path root() {
        return root;
    }

    /** Syntax-only validation for paths later traversed through secure directory handles. */
    public static List<String> segments(String relative) {
        if (relative == null || relative.isEmpty()) return List.of();
        if (relative.startsWith("/") || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0
                || relative.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("Invalid relative path");
        }
        List<String> segments = List.of(relative.split("/", -1));
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")) {
                throw new IllegalArgumentException("Invalid path segment");
            }
        }
        return segments;
    }

    public String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new SecurityException("Path leaves the configured server directory");
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }
}
