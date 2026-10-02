package com.lian.notabackdoor.panel.files;

import com.lian.notabackdoor.panel.security.PathGuard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** File operations relative to open directory handles, never to a previously checked pathname. */
public final class SecureFileRoot implements AutoCloseable {
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private final Path rootPath;
    private final SecureDirectoryStream<Path> root;

    SecureFileRoot(Path path) throws IOException {
        rootPath = new PathGuard(path).root();
        DirectoryStream<Path> opened = Files.newDirectoryStream(rootPath);
        if (!(opened instanceof SecureDirectoryStream<?>)) {
            opened.close();
            throw new IOException("This filesystem has no race-safe directory handles; panel file access is disabled");
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) opened;
        root = secure;
    }

    Path rootPath() { return rootPath; }
    SecureDirectoryStream<Path> root() { return root; }

    Directory directory(String relative) throws IOException {
        return directoryFrom(root, PathGuard.segments(relative), false);
    }

    Directory directoryFrom(SecureDirectoryStream<Path> base, List<String> segments, boolean create)
            throws IOException {
        SecureDirectoryStream<Path> current = base;
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        try {
            if (segments.isEmpty()) {
                current = base.newDirectoryStream(Path.of("."), NOFOLLOW);
                opened.add(current);
            }
            for (String segment : segments) {
                Path name = Path.of(segment);
                if (create && attributesOrNull(current, name) == null) createDirectory(current, name);
                current = current.newDirectoryStream(name, NOFOLLOW);
                opened.add(current);
            }
            return new Directory(current, opened);
        } catch (IOException | RuntimeException failure) {
            closeReverse(opened);
            throw failure;
        }
    }

    Leaf leaf(String relative) throws IOException {
        List<String> segments = PathGuard.segments(relative);
        if (segments.isEmpty()) throw new IllegalArgumentException("This operation requires a path below the file root");
        Directory parent = directoryFrom(root, segments.subList(0, segments.size() - 1), false);
        return new Leaf(parent, Path.of(segments.get(segments.size() - 1)));
    }

    BasicFileAttributes attributes(SecureDirectoryStream<Path> parent, Path name) throws IOException {
        BasicFileAttributeView view = parent.getFileAttributeView(name, BasicFileAttributeView.class, NOFOLLOW);
        if (view == null) throw new IOException("Basic file attributes are unavailable");
        return view.readAttributes();
    }

    BasicFileAttributes attributesOrNull(SecureDirectoryStream<Path> parent, Path name) throws IOException {
        try { return attributes(parent, name); }
        catch (NoSuchFileException missing) { return null; }
    }

    OpenedFile openRegularFile(String relative) throws IOException {
        try (Leaf leaf = leaf(relative)) {
            BasicFileAttributes attributes = attributes(leaf.parent.stream, leaf.name);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IllegalArgumentException("Not a regular file");
            }
            SeekableByteChannel channel = leaf.parent.stream.newByteChannel(leaf.name,
                    Set.of(StandardOpenOption.READ, NOFOLLOW));
            return new OpenedFile(leaf.name.toString(), channel);
        }
    }

    OpenedFile openRegularFile(SecureDirectoryStream<Path> parent, Path name) throws IOException {
        BasicFileAttributes attributes = attributes(parent, name);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IllegalArgumentException("Not a regular file");
        }
        return new OpenedFile(name.toString(), parent.newByteChannel(name,
                Set.of(StandardOpenOption.READ, NOFOLLOW)));
    }

    TempFile temporaryFile(SecureDirectoryStream<Path> parent, String prefix) throws IOException {
        for (int attempt = 0; attempt < 5; attempt++) {
            Path name = Path.of(prefix + UUID.randomUUID() + ".tmp");
            try {
                SeekableByteChannel channel = parent.newByteChannel(name,
                        Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, NOFOLLOW));
                return new TempFile(name, channel);
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                // The random name collided; try another one.
            }
        }
        throw new IOException("Could not allocate a temporary file");
    }

    /** mkdirat is absent from Java; create at the trusted root entry, then rename by directory handles. */
    void createDirectory(SecureDirectoryStream<Path> parent, Path name) throws IOException {
        Path temporary = Files.createTempDirectory(rootPath, ".nab-dir-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path temporaryName = temporary.getFileName();
        try {
            root.move(temporaryName, parent, name);
        } finally {
            BasicFileAttributes remaining = attributesOrNull(root, temporaryName);
            if (remaining != null) deleteTree(root, temporaryName);
        }
    }

    Path createStagingDirectory(String prefix) throws IOException {
        Path temporary = Files.createTempDirectory(rootPath, prefix,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        return temporary.getFileName();
    }

    void deleteTree(SecureDirectoryStream<Path> parent, Path name) throws IOException {
        BasicFileAttributes attributes = attributesOrNull(parent, name);
        if (attributes == null) return;
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            parent.deleteFile(name);
            return;
        }
        try (SecureDirectoryStream<Path> child = parent.newDirectoryStream(name, NOFOLLOW)) {
            for (Path entry : child) deleteTree(child, entry.getFileName());
        }
        parent.deleteDirectory(name);
    }

    @Override public void close() throws IOException { root.close(); }

    private static void closeReverse(List<SecureDirectoryStream<Path>> opened) {
        for (int i = opened.size() - 1; i >= 0; i--) {
            try { opened.get(i).close(); } catch (IOException ignored) { }
        }
    }

    static final class Directory implements AutoCloseable {
        final SecureDirectoryStream<Path> stream;
        private final List<SecureDirectoryStream<Path>> opened;
        Directory(SecureDirectoryStream<Path> stream, List<SecureDirectoryStream<Path>> opened) {
            this.stream = stream;
            this.opened = opened;
        }
        @Override public void close() { closeReverse(opened); }
    }

    static final class Leaf implements AutoCloseable {
        final Directory parent;
        final Path name;
        Leaf(Directory parent, Path name) { this.parent = parent; this.name = name; }
        @Override public void close() { parent.close(); }
    }

    public static final class OpenedFile implements AutoCloseable {
        private final String name;
        private final SeekableByteChannel channel;
        OpenedFile(String name, SeekableByteChannel channel) { this.name = name; this.channel = channel; }
        public String name() { return name; }
        public long size() throws IOException { return channel.size(); }
        public InputStream input() { return Channels.newInputStream(channel); }
        @Override public void close() throws IOException { channel.close(); }
    }

    record TempFile(Path name, SeekableByteChannel channel) implements AutoCloseable {
        @Override public void close() throws IOException { channel.close(); }
    }
}
