package com.lian.notabackdoor.panel.files;

import com.lian.notabackdoor.panel.security.PathGuard;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
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

/** Bounded server-root backups that never reopen a checked pathname. */
public final class PanelBackups implements AutoCloseable {
    private static final long MAX_BYTES = 20L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 200_000;
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private final SecureFileRoot files;
    private final String directory;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();

    public PanelBackups(Path root, Path directory) throws IOException {
        this(root, directory, Clock.systemUTC());
    }

    PanelBackups(Path root, Path directory, Clock clock) throws IOException {
        files = new SecureFileRoot(root);
        boolean ready = false;
        try {
            this.directory = new PathGuard(files.rootPath()).relative(directory);
            if (this.directory.isEmpty()) throw new IllegalArgumentException("Backups must be below the server root");
            try (SecureFileRoot.Directory ignored = files.directoryFrom(files.root(),
                    PathGuard.segments(this.directory), true)) {
                // Create the private backup directory through handle-relative moves.
            }
            this.clock = clock;
            ready = true;
        } finally {
            if (!ready) files.close();
        }
    }

    public List<Backup> list() throws IOException {
        List<Backup> result = new ArrayList<>();
        try (SecureFileRoot.Directory backups = files.directory(directory)) {
            for (Path entry : backups.stream) {
                String name = entry.getFileName().toString();
                if (!validName(name)) continue;
                BasicFileAttributes attributes = files.attributesOrNull(backups.stream, entry.getFileName());
                if (attributes != null && attributes.isRegularFile() && !attributes.isSymbolicLink()) {
                    result.add(new Backup(name, attributes.size(), attributes.lastModifiedTime().toMillis()));
                }
            }
        }
        result.sort(Comparator.comparing(Backup::createdAt).reversed());
        return result;
    }

    public Backup create() throws IOException {
        if (!running.tryLock()) throw new IllegalStateException("A backup is already in progress");
        try (SecureFileRoot.Directory backups = files.directory(directory)) {
            String filename = "backup-" + NAME.format(clock.instant()) + ".zip";
            Path destination = Path.of(filename);
            if (files.attributesOrNull(backups.stream, destination) != null) {
                throw new IllegalStateException("A backup was already created this second; retry shortly");
            }
            SecureFileRoot.TempFile temporary = files.temporaryFile(backups.stream, ".nab-backup-");
            try {
                try (temporary; ZipOutputStream output = new ZipOutputStream(Channels.newOutputStream(temporary.channel()));
                     SecureFileRoot.Directory sourceRoot = files.directory("")) {
                    backupTree(sourceRoot.stream, "", output, new long[]{0}, new int[]{0});
                }
                backups.stream.move(temporary.name(), backups.stream, destination);
            } finally {
                if (files.attributesOrNull(backups.stream, temporary.name()) != null) {
                    backups.stream.deleteFile(temporary.name());
                }
            }
            BasicFileAttributes made = files.attributes(backups.stream, destination);
            return new Backup(filename, made.size(), made.lastModifiedTime().toMillis());
        } finally {
            running.unlock();
        }
    }

    private void backupTree(SecureDirectoryStream<Path> current, String prefix, ZipOutputStream zip,
                            long[] total, int[] count) throws IOException {
        for (Path listed : current) {
            Path name = listed.getFileName();
            String relative = prefix.isEmpty() ? name.toString() : prefix + "/" + name;
            if (relative.equals(directory) || name.toString().equals("session.lock")) continue;
            try { PathGuard.segments(relative); }
            catch (IllegalArgumentException unsafeName) {
                throw new IOException("Backup source contains an unsafe file name", unsafeName);
            }
            BasicFileAttributes attributes = files.attributesOrNull(current, name);
            if (attributes == null || attributes.isSymbolicLink()) continue;
            if (++count[0] > MAX_ENTRIES) throw new IOException("Too many files for one backup");
            if (attributes.isDirectory()) {
                zip.putNextEntry(new ZipEntry(relative + "/"));
                zip.closeEntry();
                try (SecureDirectoryStream<Path> child = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    backupTree(child, relative, zip, total, count);
                }
            } else if (attributes.isRegularFile()) {
                if (attributes.size() > MAX_BYTES - total[0]) throw new IOException("Backup exceeded the 20 GiB limit");
                zip.putNextEntry(new ZipEntry(relative));
                try (SecureFileRoot.OpenedFile source = files.openRegularFile(current, name)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    var input = source.input();
                    while ((read = input.read(buffer)) != -1) {
                        if (read > MAX_BYTES - total[0]) throw new IOException("Backup exceeded the 20 GiB limit");
                        zip.write(buffer, 0, read);
                        total[0] += read;
                    }
                }
                zip.closeEntry();
            }
        }
    }

    public SecureFileRoot.OpenedFile download(String name) throws IOException {
        if (!validName(name)) throw new IllegalArgumentException("Invalid backup name");
        try (SecureFileRoot.Directory backups = files.directory(directory)) {
            return files.openRegularFile(backups.stream, Path.of(name));
        }
    }

    public void delete(String name) throws IOException {
        if (!validName(name)) throw new IllegalArgumentException("Invalid backup name");
        try (SecureFileRoot.Directory backups = files.directory(directory)) {
            BasicFileAttributes attributes = files.attributesOrNull(backups.stream, Path.of(name));
            if (attributes == null || !attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IllegalArgumentException("Backup not found");
            }
            backups.stream.deleteFile(Path.of(name));
        }
    }

    private static boolean validName(String name) {
        return name != null && name.matches("backup-[0-9]{8}-[0-9]{6}\\.zip");
    }

    @Override public void close() throws IOException { files.close(); }
    public record Backup(String name, long size, long createdAt) { }
}
