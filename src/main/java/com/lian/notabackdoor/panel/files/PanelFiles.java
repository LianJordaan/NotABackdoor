package com.lian.notabackdoor.panel.files;

import com.lian.notabackdoor.panel.security.PathGuard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Bounded server-root file operations performed through race-safe directory handles. */
public final class PanelFiles implements AutoCloseable {
    public static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;
    public static final long MAX_UPLOAD_BYTES = 128L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 20_000;
    private static final int MAX_LIST_ENTRIES = 2_000;
    private final SecureFileRoot files;

    public PanelFiles(Path root) throws IOException { files = new SecureFileRoot(root); }
    public Path root() { return files.rootPath(); }
    @Override public void close() throws IOException { files.close(); }

    public List<Entry> list(String directory) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (SecureFileRoot.Directory opened = files.directory(directory)) {
            for (Path child : opened.stream) {
                if (entries.size() >= MAX_LIST_ENTRIES) {
                    throw new IllegalArgumentException("This directory has too many entries to display");
                }
                Path name = child.getFileName();
                BasicFileAttributes attributes = files.attributesOrNull(opened.stream, name);
                if (attributes == null || attributes.isSymbolicLink()) continue;
                String path = directory.isEmpty() ? name.toString() : directory + "/" + name;
                try { PathGuard.segments(path); }
                catch (IllegalArgumentException unsafeName) { continue; }
                entries.add(new Entry(name.toString(), path, attributes.isDirectory(),
                        attributes.isDirectory() ? 0 : attributes.size(),
                        attributes.lastModifiedTime().toMillis()));
            }
        }
        entries.sort(Comparator.comparing(Entry::directory).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    public TextFile readText(String path) throws IOException {
        byte[] bytes;
        try (SecureFileRoot.OpenedFile file = files.openRegularFile(path)) {
            bytes = readBounded(file.input(), MAX_TEXT_BYTES);
        }
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
        Objects.requireNonNull(content, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("Text files are limited to 2 MiB");
        try (SecureFileRoot.Leaf target = files.leaf(path)) {
            byte[] current;
            try (SecureFileRoot.OpenedFile opened = files.openRegularFile(target.parent.stream, target.name)) {
                current = readBounded(opened.input(), MAX_TEXT_BYTES);
            }
            if (!sha256(current).equalsIgnoreCase(Objects.requireNonNull(expectedSha256, "expectedSha256"))) {
                throw new ConflictException("The file changed since it was opened. Reload before saving.");
            }
            SecureFileRoot.TempFile temporary = files.temporaryFile(target.parent.stream, ".nab-edit-");
            try {
                try (temporary) { writeAll(temporary.channel(), bytes); }
                target.parent.stream.move(temporary.name(), target.parent.stream, target.name);
            } finally {
                if (files.attributesOrNull(target.parent.stream, temporary.name()) != null) {
                    target.parent.stream.deleteFile(temporary.name());
                }
            }
        }
        return new TextFile(content, sha256(bytes));
    }

    public synchronized void create(String path, boolean directory) throws IOException {
        try (SecureFileRoot.Leaf target = files.leaf(path)) {
            if (files.attributesOrNull(target.parent.stream, target.name) != null) {
                throw new IllegalArgumentException("Destination occupied");
            }
            if (directory) files.createDirectory(target.parent.stream, target.name);
            else try (var ignored = target.parent.stream.newByteChannel(target.name,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS))) {
                // Creating the file is the entire operation.
            }
        }
    }

    public synchronized void move(String from, String to) throws IOException {
        List<String> sourceSegments = PathGuard.segments(from);
        List<String> targetSegments = PathGuard.segments(to);
        if (sourceSegments.isEmpty() || targetSegments.isEmpty()) {
            throw new IllegalArgumentException("A path below the file root is required");
        }
        if (targetSegments.size() >= sourceSegments.size()
                && targetSegments.subList(0, sourceSegments.size()).equals(sourceSegments)) {
            throw new IllegalArgumentException("A folder cannot be moved into itself");
        }
        try (SecureFileRoot.Leaf source = files.leaf(from); SecureFileRoot.Leaf target = files.leaf(to)) {
            BasicFileAttributes existing = files.attributesOrNull(source.parent.stream, source.name);
            if (existing == null || existing.isSymbolicLink()
                    || files.attributesOrNull(target.parent.stream, target.name) != null) {
                throw new IllegalArgumentException("Source missing or destination occupied");
            }
            source.parent.stream.move(source.name, target.parent.stream, target.name);
        }
    }

    public synchronized void delete(String path) throws IOException {
        try (SecureFileRoot.Leaf target = files.leaf(path)) {
            BasicFileAttributes existing = files.attributesOrNull(target.parent.stream, target.name);
            if (existing == null) throw new IllegalArgumentException("Path does not exist");
            if (existing.isSymbolicLink()) throw new SecurityException("Symbolic links are not accessible from the panel");
            files.deleteTree(target.parent.stream, target.name);
        }
    }

    public SecureFileRoot.OpenedFile download(String path) throws IOException {
        return files.openRegularFile(path);
    }

    public synchronized void upload(String path, InputStream body) throws IOException {
        try (SecureFileRoot.Leaf target = files.leaf(path)) {
            if (files.attributesOrNull(target.parent.stream, target.name) != null) {
                throw new IllegalArgumentException("Destination occupied");
            }
            SecureFileRoot.TempFile temporary = files.temporaryFile(target.parent.stream, ".nab-upload-");
            try {
                try (temporary; OutputStream output = Channels.newOutputStream(temporary.channel())) {
                    copyBounded(body, output, MAX_UPLOAD_BYTES);
                }
                target.parent.stream.move(temporary.name(), target.parent.stream, target.name);
            } finally {
                if (files.attributesOrNull(target.parent.stream, temporary.name()) != null) {
                    target.parent.stream.deleteFile(temporary.name());
                }
            }
        }
    }

    public synchronized void zip(String sourcePath, String archivePath) throws IOException {
        List<String> sourceSegments = PathGuard.segments(sourcePath);
        List<String> archiveSegments = PathGuard.segments(archivePath);
        if (sourceSegments.isEmpty() || archiveSegments.isEmpty()) {
            throw new IllegalArgumentException("A path below the file root is required");
        }
        if (archiveSegments.size() >= sourceSegments.size()
                && archiveSegments.subList(0, sourceSegments.size()).equals(sourceSegments)) {
            throw new IllegalArgumentException("An archive cannot be placed inside its source");
        }
        try (SecureFileRoot.Leaf source = files.leaf(sourcePath);
             SecureFileRoot.Leaf archive = files.leaf(archivePath)) {
            BasicFileAttributes existing = files.attributesOrNull(source.parent.stream, source.name);
            if (existing == null || existing.isSymbolicLink()
                    || files.attributesOrNull(archive.parent.stream, archive.name) != null) {
                throw new IllegalArgumentException("Invalid archive source or destination");
            }
            SecureFileRoot.TempFile temporary = files.temporaryFile(archive.parent.stream, ".nab-zip-");
            try {
                try (temporary; ZipOutputStream output = new ZipOutputStream(Channels.newOutputStream(temporary.channel()))) {
                    zipEntry(source.parent.stream, source.name, source.name.toString(), output,
                            new long[]{0}, new int[]{0});
                }
                archive.parent.stream.move(temporary.name(), archive.parent.stream, archive.name);
            } finally {
                if (files.attributesOrNull(archive.parent.stream, temporary.name()) != null) {
                    archive.parent.stream.deleteFile(temporary.name());
                }
            }
        }
    }

    private void zipEntry(SecureDirectoryStream<Path> parent, Path name, String entryName,
                          ZipOutputStream output, long[] total, int[] count) throws IOException {
        BasicFileAttributes attributes = files.attributesOrNull(parent, name);
        if (attributes == null || attributes.isSymbolicLink()) return;
        if (++count[0] > MAX_ARCHIVE_ENTRIES) throw new IOException("Archive has too many entries");
        if (attributes.isDirectory()) {
            output.putNextEntry(new ZipEntry(entryName + "/"));
            output.closeEntry();
            try (SecureDirectoryStream<Path> directory = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                for (Path child : directory) {
                    Path childName = child.getFileName();
                    try { PathGuard.segments(childName.toString()); }
                    catch (IllegalArgumentException unsafeName) { throw new IOException("Archive source contains an unsafe name", unsafeName); }
                    zipEntry(directory, childName, entryName + "/" + childName, output, total, count);
                }
            }
        } else if (attributes.isRegularFile()) {
            if (attributes.size() > MAX_EXPANDED_BYTES - total[0]) {
                throw new IOException("Archive source exceeded the 1 GiB limit");
            }
            output.putNextEntry(new ZipEntry(entryName));
            try (SecureFileRoot.OpenedFile opened = files.openRegularFile(parent, name)) {
                total[0] += copyBounded(opened.input(), output, MAX_EXPANDED_BYTES - total[0]);
            }
            output.closeEntry();
        }
    }

    public synchronized void unzip(String archivePath, String destinationPath) throws IOException {
        try (SecureFileRoot.OpenedFile archive = files.openRegularFile(archivePath);
             SecureFileRoot.Leaf destination = files.leaf(destinationPath)) {
            if (files.attributesOrNull(destination.parent.stream, destination.name) != null) {
                throw new IllegalArgumentException("Extraction destination must be a new directory");
            }
            Path stagingName = files.createStagingDirectory(".nab-extract-");
            boolean completed = false;
            try {
                try (SecureDirectoryStream<Path> staging = files.root().newDirectoryStream(
                        stagingName, LinkOption.NOFOLLOW_LINKS);
                     ZipInputStream zip = new ZipInputStream(archive.input())) {
                    long total = 0;
                    int count = 0;
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (++count > MAX_ARCHIVE_ENTRIES) throw new IOException("Archive has too many entries");
                        String name = entry.getName();
                        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                        if (name.isEmpty()) continue;
                        List<String> segments = PathGuard.segments(name);
                        int parentCount = entry.isDirectory() ? segments.size() : segments.size() - 1;
                        try (SecureFileRoot.Directory parent = files.directoryFrom(
                                staging, segments.subList(0, parentCount), true)) {
                            if (!entry.isDirectory()) {
                                Path leaf = Path.of(segments.get(segments.size() - 1));
                                try (var channel = parent.stream.newByteChannel(leaf,
                                        Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW,
                                                LinkOption.NOFOLLOW_LINKS));
                                     OutputStream output = Channels.newOutputStream(channel)) {
                                    total += copyBounded(zip, output, MAX_EXPANDED_BYTES - total);
                                }
                            }
                        }
                        zip.closeEntry();
                    }
                }
                files.root().move(stagingName, destination.parent.stream, destination.name);
                completed = true;
            } finally {
                if (!completed) files.deleteTree(files.root(), stagingName);
            }
        }
    }

    private static byte[] readBounded(InputStream input, long limit) throws IOException {
        byte[] bytes = input.readNBytes(Math.toIntExact(limit + 1));
        if (bytes.length > limit) throw new IllegalArgumentException("File is too large");
        return bytes;
    }

    private static long copyBounded(InputStream source, OutputStream target, long limit) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = source.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("Transfer exceeds the size limit");
            target.write(buffer, 0, read);
        }
        return total;
    }

    private static void writeAll(java.nio.channels.SeekableByteChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record Entry(String name, String path, boolean directory, long size, long modifiedAt) { }
    public record TextFile(String content, String sha256) { }
    public static final class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }
}
