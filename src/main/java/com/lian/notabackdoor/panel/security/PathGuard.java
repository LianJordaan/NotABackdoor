package com.lian.notabackdoor.panel.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Constrains panel file actions to the selected server directory. */
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

    public Path resolve(String relative) throws IOException {
        if (relative == null || relative.isEmpty()) {
            return root;
        }
        if (relative.startsWith("/") || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0
                || relative.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("Invalid relative path");
        }

        Path current = root;
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")) {
                throw new IllegalArgumentException("Invalid path segment");
            }
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new SecurityException("Symbolic links are not accessible from the panel");
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !current.toRealPath().startsWith(root)) {
                throw new SecurityException("Path leaves the configured server directory");
            }
        }
        Path result = current.normalize();
        if (!result.startsWith(root)) {
            throw new SecurityException("Path leaves the configured server directory");
        }
        return result;
    }

    public Path resolveChild(String relative) throws IOException {
        Path child = resolve(relative);
        if (child.equals(root)) {
            throw new IllegalArgumentException("This operation requires a path below the file root");
        }
        return child;
    }

    public String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new SecurityException("Path leaves the configured server directory");
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }
}
