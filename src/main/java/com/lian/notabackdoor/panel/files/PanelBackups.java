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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
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
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService jobs = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "notabackdoor-backup");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Job> recentJobs = new ConcurrentHashMap<>();

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
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("A backup is already in progress");
        try { return createArchive(null); }
        finally { running.set(false); }
    }

    /** Start an archive without holding an HTTP worker for the duration of the copy. */
    public BackupJob start(Callable<Void> saveWorlds) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("A backup is already in progress");
        Job job = new Job(UUID.randomUUID().toString(), clock.millis());
        recentJobs.put(job.id, job);
        if (recentJobs.size() > 16) {
            recentJobs.values().stream().sorted(Comparator.comparingLong(value -> value.startedAt))
                    .limit(recentJobs.size() - 16L).forEach(value -> recentJobs.remove(value.id, value));
        }
        try {
            jobs.execute(() -> {
                try {
                    job.phase = "saving";
                    saveWorlds.call();
                    job.phase = "scanning";
                    long[] totals = new long[2];
                    try (SecureFileRoot.Directory root = files.directory("")) {
                        scanTree(root.stream, "", totals, new int[]{0});
                    }
                    job.totalBytes = totals[0];
                    job.totalFiles = totals[1];
                    job.phase = "archiving";
                    job.backup = createArchive(job);
                    job.phase = "completed";
                } catch (Exception failure) {
                    job.error = failure.getMessage() == null ? "Backup failed" : failure.getMessage();
                    job.phase = "failed";
                } finally {
                    job.finishedAt = clock.millis();
                    running.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            recentJobs.remove(job.id);
            running.set(false);
            throw rejected;
        }
        return job.snapshot();
    }

    public BackupJob job(String id) {
        Job found = recentJobs.get(id);
        if (found == null) throw new IllegalArgumentException("Unknown backup job");
        return found.snapshot();
    }

    public BackupJob latestJob() {
        return recentJobs.values().stream()
                .max(Comparator.comparingLong(value -> value.startedAt))
                .map(Job::snapshot).orElse(null);
    }

    private Backup createArchive(Job progress) throws IOException {
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
                    backupTree(sourceRoot.stream, "", output, new long[]{0}, new int[]{0}, progress);
                }
                backups.stream.move(temporary.name(), backups.stream, destination);
            } finally {
                if (files.attributesOrNull(backups.stream, temporary.name()) != null) {
                    backups.stream.deleteFile(temporary.name());
                }
            }
            BasicFileAttributes made = files.attributes(backups.stream, destination);
            return new Backup(filename, made.size(), made.lastModifiedTime().toMillis());
        }
    }

    private void scanTree(SecureDirectoryStream<Path> current, String prefix,
                          long[] totals, int[] count) throws IOException {
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
                try (SecureDirectoryStream<Path> child = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    scanTree(child, relative, totals, count);
                }
            } else if (attributes.isRegularFile()) {
                if (attributes.size() > MAX_BYTES - totals[0]) throw new IOException("Backup exceeded the 20 GiB limit");
                totals[0] += attributes.size();
                totals[1]++;
            }
        }
    }

    private void backupTree(SecureDirectoryStream<Path> current, String prefix, ZipOutputStream zip,
                            long[] total, int[] count, Job progress) throws IOException {
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
                    backupTree(child, relative, zip, total, count, progress);
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
                        if (progress != null) progress.bytesDone = total[0];
                    }
                }
                zip.closeEntry();
                if (progress != null) progress.filesDone++;
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

    @Override public void close() throws IOException {
        jobs.shutdownNow();
        try { jobs.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        files.close();
    }
    public record Backup(String name, long size, long createdAt) { }
    public record BackupJob(String id, String phase, long startedAt, long finishedAt,
                            long totalBytes, long bytesDone, long totalFiles, long filesDone,
                            Backup backup, String error) { }

    private static final class Job {
        private final String id;
        private final long startedAt;
        private volatile String phase = "queued";
        private volatile long finishedAt, totalBytes, bytesDone, totalFiles, filesDone;
        private volatile Backup backup;
        private volatile String error;

        private Job(String id, long startedAt) {
            this.id = id;
            this.startedAt = startedAt;
        }

        private BackupJob snapshot() {
            return new BackupJob(id, phase, startedAt, finishedAt,
                    totalBytes, bytesDone, totalFiles, filesDone, backup, error);
        }
    }
}
