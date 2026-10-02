package com.lian.notabackdoor.panel.files;

import com.lian.notabackdoor.panel.security.PathGuard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private static final int MAX_SELECTIONS = 2_000;
    private static final byte[] TAR_PADDING = new byte[512];
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
        zip(List.of(sourcePath), archivePath);
    }

    /** Creates one ZIP from the selected files and directories, relative to their common parent. */
    public synchronized void zip(List<String> sourcePaths, String archivePath) throws IOException {
        List<Source> sources = sources(sourcePaths);
        List<String> archiveSegments = PathGuard.segments(archivePath);
        if (archiveSegments.isEmpty()) {
            throw new IllegalArgumentException("A path below the file root is required");
        }
        for (Source source : sources) {
            if (containsPath(archiveSegments, source.segments)) {
                throw new IllegalArgumentException("An archive cannot be placed inside its source");
            }
        }
        try (SecureFileRoot.Leaf archive = files.leaf(archivePath)) {
            if (files.attributesOrNull(archive.parent.stream, archive.name) != null) {
                throw new IllegalArgumentException("Archive destination is occupied");
            }
            SecureFileRoot.TempFile temporary = files.temporaryFile(archive.parent.stream, ".nab-zip-");
            try {
                try (temporary; ZipOutputStream output = new ZipOutputStream(Channels.newOutputStream(temporary.channel()))) {
                    long[] total = {0};
                    int[] count = {0};
                    for (Source source : sources) {
                        try (SecureFileRoot.Leaf opened = files.leaf(source.path)) {
                            zipEntry(opened.parent.stream, opened.name, source.archiveName,
                                    output, total, count);
                        }
                    }
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
        if (attributes == null) throw new IOException("Archive source changed during creation");
        if (attributes.isSymbolicLink()) throw new SecurityException("Symbolic links cannot be archived");
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
        } else throw new IOException("Only regular files and directories can be archived");
    }

    /** Returns a temporary TAR outside the selectable server root. Always close after sending it. */
    public synchronized DownloadArchive tar(List<String> sourcePaths) throws IOException {
        List<Source> sources = sources(sourcePaths);
        Path temporary = Files.createTempFile("nab-download-", ".tar").toAbsolutePath().normalize();
        if (temporary.startsWith(files.rootPath())) {
            Files.deleteIfExists(temporary);
            throw new IOException("The temporary download directory must be outside the server file root");
        }
        SeekableByteChannel channel = null;
        try {
            channel = Files.newByteChannel(temporary,
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
            OutputStream output = Channels.newOutputStream(channel);
            long[] total = {0};
            int[] count = {0};
            for (Source source : sources) {
                try (SecureFileRoot.Leaf opened = files.leaf(source.path)) {
                    tarEntry(opened.parent.stream, opened.name, source.archiveName, output, total, count);
                }
            }
            output.write(TAR_PADDING);
            output.write(TAR_PADDING);
            channel.position(0);
            return new DownloadArchive("server-files.tar", temporary, channel);
        } catch (IOException | RuntimeException failure) {
            if (channel != null) channel.close();
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    /** Validates the whole selection before deleting any item. A later filesystem error may still be partial. */
    public synchronized void deleteMany(List<String> sourcePaths) throws IOException {
        List<Source> sources = sources(sourcePaths);
        for (Source source : sources) {
            try (SecureFileRoot.Leaf target = files.leaf(source.path)) {
                BasicFileAttributes existing = files.attributesOrNull(target.parent.stream, target.name);
                if (existing == null) throw new IllegalArgumentException("Path does not exist: " + source.path);
                if (existing.isSymbolicLink()) throw new SecurityException("Symbolic links are not accessible from the panel");
                files.deleteTree(target.parent.stream, target.name);
            }
        }
    }

    private List<Source> sources(List<String> sourcePaths) throws IOException {
        Objects.requireNonNull(sourcePaths, "sourcePaths");
        if (sourcePaths.isEmpty() || sourcePaths.size() > MAX_SELECTIONS) {
            throw new IllegalArgumentException("Select between 1 and 2,000 files or folders");
        }
        List<List<String>> paths = new ArrayList<>(sourcePaths.size());
        for (String path : sourcePaths) {
            List<String> segments = PathGuard.segments(path);
            if (segments.isEmpty()) throw new IllegalArgumentException("The file root cannot be selected");
            for (List<String> previous : paths) {
                if (containsPath(segments, previous) || containsPath(previous, segments)) {
                    throw new IllegalArgumentException("The selection contains duplicate or overlapping paths");
                }
            }
            try (SecureFileRoot.Leaf opened = files.leaf(path)) {
                BasicFileAttributes attributes = files.attributesOrNull(opened.parent.stream, opened.name);
                if (attributes == null) throw new IllegalArgumentException("Path does not exist: " + path);
                if (attributes.isSymbolicLink()) throw new SecurityException("Symbolic links are not accessible from the panel");
                if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                    throw new IllegalArgumentException("Only regular files and directories can be selected");
                }
            }
            paths.add(segments);
        }
        List<String> commonParent = new ArrayList<>(paths.get(0).subList(0, paths.get(0).size() - 1));
        for (List<String> path : paths) {
            while (!containsPath(path.subList(0, path.size() - 1), commonParent)) {
                commonParent.remove(commonParent.size() - 1);
            }
        }
        List<Source> sources = new ArrayList<>(paths.size());
        for (int index = 0; index < paths.size(); index++) {
            List<String> segments = paths.get(index);
            sources.add(new Source(sourcePaths.get(index), segments,
                    String.join("/", segments.subList(commonParent.size(), segments.size()))));
        }
        return sources;
    }

    private static boolean containsPath(List<String> path, List<String> prefix) {
        return path.size() >= prefix.size() && path.subList(0, prefix.size()).equals(prefix);
    }

    private void tarEntry(SecureDirectoryStream<Path> parent, Path name, String entryName,
                          OutputStream output, long[] total, int[] count) throws IOException {
        BasicFileAttributes attributes = files.attributesOrNull(parent, name);
        if (attributes == null) throw new IOException("Archive source changed during creation");
        if (attributes.isSymbolicLink()) throw new SecurityException("Symbolic links cannot be archived");
        if (++count[0] > MAX_ARCHIVE_ENTRIES) throw new IOException("Archive has too many entries");
        long modified = Math.max(0, attributes.lastModifiedTime().toMillis() / 1000);
        if (attributes.isDirectory()) {
            writeTarHeader(output, entryName + "/", 0, modified, '5');
            try (SecureDirectoryStream<Path> directory = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                for (Path child : directory) {
                    Path childName = child.getFileName();
                    try { PathGuard.segments(childName.toString()); }
                    catch (IllegalArgumentException unsafeName) {
                        throw new IOException("Archive source contains an unsafe name", unsafeName);
                    }
                    tarEntry(directory, childName, entryName + "/" + childName, output, total, count);
                }
            }
        } else if (attributes.isRegularFile()) {
            if (attributes.size() > MAX_EXPANDED_BYTES - total[0]) {
                throw new IOException("Archive source exceeded the 1 GiB limit");
            }
            try (SecureFileRoot.OpenedFile opened = files.openRegularFile(parent, name)) {
                long size = opened.size();
                if (size > MAX_EXPANDED_BYTES - total[0]) {
                    throw new IOException("Archive source exceeded the 1 GiB limit");
                }
                writeTarHeader(output, entryName, size, modified, '0');
                copyExactly(opened.input(), output, size);
                padTar(output, size);
                total[0] += size;
            }
        } else throw new IOException("Only regular files and directories can be archived");
    }

    private static void writeTarHeader(OutputStream output, String path, long size, long modified, char type)
            throws IOException {
        byte[] name = path.getBytes(StandardCharsets.UTF_8);
        if (name.length > 4_096) throw new IOException("Archive path is too long");
        String headerName = path;
        if (name.length > 100 || !StandardCharsets.US_ASCII.newEncoder().canEncode(path)) {
            byte[] extendedPath = paxPath(path);
            output.write(tarHeader("PaxHeaders/path", extendedPath.length, modified, 'x'));
            output.write(extendedPath);
            padTar(output, extendedPath.length);
            headerName = "item";
        }
        output.write(tarHeader(headerName, size, modified, type));
    }

    private static byte[] paxPath(String path) {
        byte[] value = ("path=" + path + "\n").getBytes(StandardCharsets.UTF_8);
        int length = value.length + 2;
        while (true) {
            byte[] prefix = (length + " ").getBytes(StandardCharsets.US_ASCII);
            int actual = prefix.length + value.length;
            if (actual == length) {
                byte[] record = new byte[actual];
                System.arraycopy(prefix, 0, record, 0, prefix.length);
                System.arraycopy(value, 0, record, prefix.length, value.length);
                return record;
            }
            length = actual;
        }
    }

    private static byte[] tarHeader(String name, long size, long modified, char type) throws IOException {
        byte[] header = new byte[512];
        putAscii(header, 0, 100, name);
        putOctal(header, 100, 8, type == '5' ? 0755 : 0644);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, size);
        putOctal(header, 136, 12, modified);
        for (int i = 148; i < 156; i++) header[i] = ' ';
        header[156] = (byte) type;
        putAscii(header, 257, 6, "ustar");
        putAscii(header, 263, 2, "00");
        long checksum = 0;
        for (byte item : header) checksum += Byte.toUnsignedInt(item);
        putOctal(header, 148, 7, checksum);
        header[155] = ' ';
        return header;
    }

    private static void putAscii(byte[] bytes, int offset, int length, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        if (encoded.length > length) throw new IOException("Archive header field is too long");
        System.arraycopy(encoded, 0, bytes, offset, encoded.length);
    }

    private static void putOctal(byte[] bytes, int offset, int length, long value) throws IOException {
        String encoded = Long.toOctalString(value);
        if (value < 0 || encoded.length() > length - 1) throw new IOException("Archive numeric field is too large");
        for (int index = 0; index < length - encoded.length() - 1; index++) bytes[offset + index] = '0';
        putAscii(bytes, offset + length - encoded.length() - 1, encoded.length(), encoded);
    }

    private static void padTar(OutputStream output, long size) throws IOException {
        int padding = (int) ((512 - size % 512) % 512);
        output.write(TAR_PADDING, 0, padding);
    }

    private static void copyExactly(InputStream source, OutputStream target, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Archive source changed during creation");
            target.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private record Source(String path, List<String> segments, String archiveName) { }

    public static final class DownloadArchive implements AutoCloseable {
        private final String name;
        private final Path temporary;
        private final SeekableByteChannel channel;
        private DownloadArchive(String name, Path temporary, SeekableByteChannel channel) {
            this.name = name;
            this.temporary = temporary;
            this.channel = channel;
        }
        public String name() { return name; }
        public long size() throws IOException { return channel.size(); }
        public InputStream input() { return Channels.newInputStream(channel); }
        Path temporaryPath() { return temporary; }
        @Override public void close() throws IOException {
            try { channel.close(); }
            finally { Files.deleteIfExists(temporary); }
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
