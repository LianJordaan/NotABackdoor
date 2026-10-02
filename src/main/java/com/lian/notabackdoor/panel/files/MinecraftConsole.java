package com.lian.notabackdoor.panel.files;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Reads Minecraft's actual latest.log, including output written before the panel started. */
public final class MinecraftConsole implements AutoCloseable {
    private static final int MAX_READ_BYTES = 256 * 1024;
    private static final int MAX_LINES = 800;
    private static final Path LATEST = Path.of("latest.log");
    private final SecureFileRoot root;

    public MinecraftConsole(Path serverRoot) throws IOException {
        root = new SecureFileRoot(serverRoot);
    }

    /**
     * The opaque cursor combines a file identity with a byte offset. Rotation and truncation
     * cause a fresh tail rather than silently mixing two server sessions.
     */
    public Batch read(String cursor) throws IOException {
        try (SecureFileRoot.Directory logs = root.directory("logs")) {
            BasicFileAttributes attributes;
            try {
                attributes = root.attributes(logs.stream, LATEST);
            } catch (NoSuchFileException missing) {
                return new Batch(List.of(), "", true, false, false);
            }
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new SecurityException("The server console log must be a regular file");
            }
            String identity = identity(attributes);
            try (SeekableByteChannel channel = logs.stream.newByteChannel(LATEST,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                long size = channel.size();
                long previousOffset = parseOffset(cursor, identity);
                boolean reset = previousOffset < 0 || previousOffset > size;
                long start = reset ? Math.max(0, size - MAX_READ_BYTES) : previousOffset;
                boolean truncated = reset && start > 0;
                if (size - start > MAX_READ_BYTES) {
                    start = size - MAX_READ_BYTES;
                    truncated = true;
                    reset = true;
                }
                channel.position(start);
                int length = Math.toIntExact(Math.min(size - start, MAX_READ_BYTES));
                ByteBuffer buffer = ByteBuffer.allocate(length);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
                int count = buffer.position();
                byte[] bytes = buffer.array();
                int from = 0;
                if (start > 0 && reset) {
                    while (from < count && bytes[from++] != '\n') { }
                    if (from == count && (count == 0 || bytes[count - 1] != '\n')) {
                        return new Batch(List.of(), identity + ":" + (start + count), true, true, true);
                    }
                }
                ArrayDeque<String> lines = new ArrayDeque<>();
                int consumed = from;
                int lineStart = from;
                for (int index = from; index < count; index++) {
                    if (bytes[index] != '\n') continue;
                    int end = index > lineStart && bytes[index - 1] == '\r' ? index - 1 : index;
                    String line = new String(bytes, lineStart, end - lineStart, StandardCharsets.UTF_8);
                    if (lines.size() == MAX_LINES) {
                        lines.removeFirst();
                        truncated = true;
                    }
                    lines.addLast(line);
                    consumed = index + 1;
                    lineStart = consumed;
                }
                // Keep an unfinished line for the next poll. Oversized lines cannot block progress forever.
                if (consumed == 0 && count == MAX_READ_BYTES && start == 0) {
                    consumed = count;
                    truncated = true;
                }
                return new Batch(new ArrayList<>(lines), identity + ":" + (start + consumed),
                        reset, truncated, true);
            }
        } catch (NoSuchFileException missingLogsDirectory) {
            return new Batch(List.of(), "", true, false, false);
        }
    }

    private static long parseOffset(String cursor, String identity) {
        if (cursor == null || cursor.isBlank()) return -1;
        int separator = cursor.indexOf(':');
        if (separator <= 0 || !identity.equals(cursor.substring(0, separator))) return -1;
        try {
            return Long.parseLong(cursor.substring(separator + 1));
        } catch (NumberFormatException badCursor) {
            return -1;
        }
    }

    private static String identity(BasicFileAttributes attributes) {
        String value = attributes.fileKey() == null
                ? Long.toString(attributes.creationTime().toMillis()) : attributes.fileKey().toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override public void close() throws IOException { root.close(); }

    public record Batch(List<String> lines, String cursor, boolean reset, boolean truncated, boolean available) { }
}
