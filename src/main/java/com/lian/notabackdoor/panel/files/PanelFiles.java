package com.lian.notabackdoor.panel.files;

import com.lian.notabackdoor.panel.security.PathGuard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Bounded, server-root-scoped file operations. No path supplied by a browser is used directly. */
public final class PanelFiles {
    public static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;
    public static final long MAX_UPLOAD_BYTES = 128L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 20_000;
    private static final int MAX_LIST_ENTRIES = 2_000;
    private final PathGuard guard;

    public PanelFiles(Path root) throws IOException {
        guard = new PathGuard(root);
    }

    public Path root() {
        return guard.root();
    }

    public Path guarded(String path) throws IOException {
        return guard.resolve(path);
    }

    public List<Entry> list(String directory) throws IOException {
        Path target = guard.resolve(directory);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Not a directory");
        }
        List<Entry> entries = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(target)) {
            for (Path child : stream) {
                if (entries.size() >= MAX_LIST_ENTRIES) {
                    throw new IllegalArgumentException("This directory has too many entries to display");
                }
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                boolean folder = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                entries.add(new Entry(child.getFileName().toString(), guard.relative(child), folder,
                        folder ? 0 : Files.size(child), Files.getLastModifiedTime(child).toMillis()));
            }
        }
        entries.sort(Comparator.comparing(Entry::directory).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    public TextFile readText(String path) throws IOException {
        Path target = existingFile(path);
        byte[] bytes = limitedRead(target, MAX_TEXT_BYTES);
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException binary) {
            throw new IllegalArgumentException("This file is not UTF-8 text");
        }
        return new TextFile(content, sha256(bytes));
    }

    public synchronized TextFile writeText(String path, String content, String expectedSha256) throws IOException {
        Path target = guard.resolveChild(path);
        Objects.requireNonNull(content, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Text files are limited to 2 MiB");
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("File does not exist");
        }
        String currentHash = sha256(limitedRead(target, MAX_TEXT_BYTES));
        if (!currentHash.equalsIgnoreCase(Objects.requireNonNull(expectedSha256, "expectedSha256"))) {
            throw new ConflictException("The file changed since it was opened. Reload before saving.");
        }
        Path temporary = Files.createTempFile(target.getParent(), ".nab-edit-", ".tmp");
        try {
            Files.write(temporary, bytes);
            moveAtomic(temporary, target, true);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new TextFile(content, sha256(bytes));
    }

    public synchronized void create(String path, boolean directory) throws IOException {
        Path target = guard.resolveChild(path);
        if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Parent directory does not exist");
        }
        if (directory) {
            Files.createDirectory(target);
        } else {
            Files.createFile(target);
        }
    }

    public synchronized void move(String from, String to) throws IOException {
        Path source = guard.resolveChild(from);
        Path target = guard.resolveChild(to);
        if (target.startsWith(source)) {
            throw new IllegalArgumentException("A folder cannot be moved into itself");
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Source missing, destination occupied, or parent missing");
        }
        moveAtomic(source, target, false);
    }

    public synchronized void delete(String path) throws IOException {
        Path target = guard.resolveChild(path);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Path does not exist");
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public Path download(String path) throws IOException {
        return existingFile(path);
    }

    public synchronized void upload(String path, InputStream body) throws IOException {
        Path target = guard.resolveChild(path);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Destination occupied or parent missing");
        }
        Path temporary = Files.createTempFile(target.getParent(), ".nab-upload-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                copyBounded(body, output, MAX_UPLOAD_BYTES);
            }
            moveAtomic(temporary, target, false);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized void zip(String sourcePath, String archivePath) throws IOException {
        Path source = guard.resolveChild(sourcePath);
        Path archive = guard.resolveChild(archivePath);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)
                || Files.exists(archive, LinkOption.NOFOLLOW_LINKS)
                || archive.startsWith(source)) {
            throw new IllegalArgumentException("Invalid archive source or destination");
        }
        Path temporary = Files.createTempFile(archive.getParent(), ".nab-zip-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                int[] count = {0};
                long[] total = {0};
                Path parent = source.getParent();
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                        if (++count[0] > MAX_ARCHIVE_ENTRIES) throw new IOException("Archive has too many entries");
                        zip.putNextEntry(new ZipEntry(parent.relativize(directory).toString().replace('\\', '/') + "/"));
                        zip.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) return FileVisitResult.CONTINUE;
                        if (++count[0] > MAX_ARCHIVE_ENTRIES) throw new IOException("Archive has too many entries");
                        if (attributes.size() > MAX_EXPANDED_BYTES - total[0]) {
                            throw new IOException("Archive source exceeded the 1 GiB limit");
                        }
                        zip.putNextEntry(new ZipEntry(parent.relativize(file).toString().replace('\\', '/')));
                        try (InputStream input = Files.newInputStream(file)) {
                            total[0] += copyBounded(input, zip, MAX_EXPANDED_BYTES - total[0]);
                        }
                        zip.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            moveAtomic(temporary, archive, false);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized void unzip(String archivePath, String destinationPath) throws IOException {
        Path archive = existingFile(archivePath);
        Path destination = guard.resolveChild(destinationPath);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(destination.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Extraction destination must be a new directory");
        }
        Path staging = Files.createTempDirectory(destination.getParent(), ".nab-extract-");
        boolean completed = false;
        try {
            PathGuard stageGuard = new PathGuard(staging);
            long total = 0;
            int count = 0;
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++count > MAX_ARCHIVE_ENTRIES) throw new IOException("Archive has too many entries");
                    String name = entry.getName();
                    if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                    if (name.isEmpty()) continue;
                    Path output = stageGuard.resolveChild(name);
                    if (entry.isDirectory()) {
                        Files.createDirectories(output);
                    } else {
                        Files.createDirectories(output.getParent());
                        try (OutputStream file = Files.newOutputStream(output,
                                java.nio.file.StandardOpenOption.CREATE_NEW)) {
                            total += copyBounded(zip, file, MAX_EXPANDED_BYTES - total);
                        }
                    }
                    zip.closeEntry();
                }
            }
            moveAtomic(staging, destination, false);
            completed = true;
        } finally {
            if (!completed) deleteTree(staging);
        }
    }

    private Path existingFile(String path) throws IOException {
        Path target = guard.resolveChild(path);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Not a regular file");
        }
        return target;
    }

    private static byte[] limitedRead(Path target, long limit) throws IOException {
        if (Files.size(target) > limit) throw new IllegalArgumentException("File is too large");
        return Files.readAllBytes(target);
    }

    private static long copyBounded(InputStream source, OutputStream target, long limit) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = source.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IllegalArgumentException("Transfer exceeds the size limit");
            target.write(buffer, 0, read);
        }
        return total;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void moveAtomic(Path source, Path destination, boolean replace) throws IOException {
        try {
            if (replace) Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            if (replace) Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, destination);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record Entry(String name, String path, boolean directory, long size, long modifiedAt) { }
    public record TextFile(String content, String sha256) { }
    public static final class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }
}
