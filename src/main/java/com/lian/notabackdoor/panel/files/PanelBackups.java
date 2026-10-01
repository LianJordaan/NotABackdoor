package com.lian.notabackdoor.panel.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Server-root backup archives with bounded size and an excluded backup destination. */
public final class PanelBackups {
    private static final long MAX_BYTES = 20L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 200_000;
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private final Path root;
    private final Path directory;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();

    public PanelBackups(Path root, Path directory) throws IOException {
        this(root, directory, Clock.systemUTC());
    }

    PanelBackups(Path root, Path directory, Clock clock) throws IOException {
        this.root = root.toRealPath();
        Files.createDirectories(directory);
        this.directory = directory.toRealPath();
        if (!this.directory.startsWith(this.root)) {
            throw new IllegalArgumentException("Backups must live under the selected server root");
        }
        this.clock = clock;
    }

    public List<Backup> list() throws IOException {
        List<Backup> result = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(directory, "backup-*.zip")) {
            for (Path archive : stream) {
                if (Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                    result.add(new Backup(archive.getFileName().toString(), Files.size(archive),
                            Files.getLastModifiedTime(archive).toMillis()));
                }
            }
        }
        result.sort(Comparator.comparing(Backup::createdAt).reversed());
        return result;
    }

    public Backup create() throws IOException {
        if (!running.tryLock()) throw new IllegalStateException("A backup is already in progress");
        try {
            String filename = "backup-" + NAME.format(clock.instant()) + ".zip";
            Path destination = directory.resolve(filename);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("A backup was already created this second; retry shortly");
            }
            Path temporary = Files.createTempFile(directory, ".nab-backup-", ".tmp");
            try {
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                    long[] total = {0};
                    int[] count = {0};
                    Files.walkFileTree(root, new SimpleFileVisitor<>() {
                        @Override public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) throws IOException {
                            if (folder.equals(directory)) return FileVisitResult.SKIP_SUBTREE;
                            if (!folder.equals(root)) {
                                if (++count[0] > MAX_ENTRIES) throw new IOException("Too many files for one backup");
                                zip.putNextEntry(new ZipEntry(name(folder) + "/"));
                                zip.closeEntry();
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                            if (attributes.isSymbolicLink() || !attributes.isRegularFile()
                                    || file.getFileName().toString().equals("session.lock")) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (++count[0] > MAX_ENTRIES) throw new IOException("Too many files for one backup");
                            if (attributes.size() > MAX_BYTES - total[0]) {
                                throw new IOException("Backup exceeded the 20 GiB limit");
                            }
                            zip.putNextEntry(new ZipEntry(name(file)));
                            // A live server can append to a file after its size was checked.
                            // Count actual copied bytes, and do not follow a replacement symlink.
                            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                                byte[] buffer = new byte[64 * 1024];
                                int read;
                                while ((read = input.read(buffer)) != -1) {
                                    if (read > MAX_BYTES - total[0]) {
                                        throw new IOException("Backup exceeded the 20 GiB limit");
                                    }
                                    zip.write(buffer, 0, read);
                                    total[0] += read;
                                }
                            }
                            zip.closeEntry();
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, destination);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return new Backup(filename, Files.size(destination), Files.getLastModifiedTime(destination).toMillis());
        } finally {
            running.unlock();
        }
    }

    public Path download(String name) {
        if (name == null || !name.matches("backup-[0-9]{8}-[0-9]{6}\\.zip")) {
            throw new IllegalArgumentException("Invalid backup name");
        }
        Path archive = directory.resolve(name);
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Backup not found");
        }
        return archive;
    }

    public void delete(String name) throws IOException {
        Files.delete(download(name));
    }

    private String name(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    public record Backup(String name, long size, long createdAt) { }
}
