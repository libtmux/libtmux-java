package io.github.libtmux.tools.mcpswap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

final class FileSnapshot {
    private static final long MAX_CONFIG_BYTES = 16L * 1024 * 1024;

    private final Path path;
    private final boolean exists;
    private final String identity;
    private final String permissions;
    private final int links;
    private final long size;
    private final String modified;
    private final String digest;
    private final byte[] data;

    FileSnapshot(
            Path path,
            boolean exists,
            String identity,
            String permissions,
            int links,
            long size,
            String modified,
            String digest,
            byte[] data) {
        this.path = path;
        this.exists = exists;
        this.identity = identity;
        this.permissions = permissions;
        this.links = links;
        this.size = size;
        this.modified = modified;
        this.digest = digest;
        this.data = data.clone();
    }

    static FileSnapshot capture(Path path) throws IOException {
        return capture(path, null);
    }

    static FileSnapshot capture(Path path, @Nullable FileChannel channel) throws IOException {
        var normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return new FileSnapshot(normalized, false, "", "", 0, 0, "", "", new byte[0]);
        }
        var basic = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!basic.isRegularFile()) {
            throw new IOException("path is not a regular file: " + normalized);
        }
        if (basic.size() > MAX_CONFIG_BYTES) {
            throw new IOException("file exceeds 16 MiB: " + normalized);
        }
        if (channel == null) {
            SwapLock.rejectAlias(String.valueOf(basic.fileKey()), normalized);
        }
        var posix = Files.readAttributes(normalized, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var links = links(normalized);
        var data = channel == null ? Files.readAllBytes(normalized) : read(channel, basic.size());
        var after = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var posixAfter = Files.readAttributes(normalized, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var linksAfter = links(normalized);
        if (!String.valueOf(basic.fileKey()).equals(String.valueOf(after.fileKey()))
                || basic.size() != after.size()
                || !basic.lastModifiedTime().equals(after.lastModifiedTime())
                || !posix.permissions().equals(posixAfter.permissions())
                || links != linksAfter) {
            throw new IOException("file changed while it was read: " + normalized);
        }
        return new FileSnapshot(
                normalized,
                true,
                String.valueOf(basic.fileKey()),
                PosixFilePermissions.toString(posix.permissions()),
                links,
                basic.size(),
                basic.lastModifiedTime().toInstant().toString(),
                sha256(data),
                data);
    }

    byte[] bytes() {
        return data.clone();
    }

    Path path() {
        return path;
    }

    boolean exists() {
        return exists;
    }

    String identity() {
        return identity;
    }

    String permissions() {
        return permissions;
    }

    int links() {
        return links;
    }

    long size() {
        return size;
    }

    String modified() {
        return modified;
    }

    String digest() {
        return digest;
    }

    boolean same(FileSnapshot other) {
        return path.equals(other.path) && sameFile(other);
    }

    boolean sameFile(FileSnapshot other) {
        return exists == other.exists
                && identity.equals(other.identity)
                && permissions.equals(other.permissions)
                && links == other.links
                && size == other.size
                && modified.equals(other.modified)
                && digest.equals(other.digest)
                && Arrays.equals(data, other.data);
    }

    boolean sameExceptLinks(FileSnapshot other) {
        return exists == other.exists
                && identity.equals(other.identity)
                && permissions.equals(other.permissions)
                && size == other.size
                && modified.equals(other.modified)
                && digest.equals(other.digest)
                && Arrays.equals(data, other.data);
    }

    void verify() throws IOException {
        if (!same(capture(path))) {
            throw new IOException("file changed: " + path);
        }
    }

    void verify(FileChannel channel) throws IOException {
        if (!same(capture(path, channel))) {
            throw new IOException("file changed: " + path);
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static int links(Path path) throws IOException {
        var raw = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        return raw instanceof Number number ? number.intValue() : 0;
    }

    private static byte[] read(FileChannel channel, long expectedSize) throws IOException {
        if (channel.size() != expectedSize) {
            throw new IOException("open file does not match path size");
        }
        var data = new byte[Math.toIntExact(expectedSize)];
        var buffer = ByteBuffer.wrap(data);
        long offset = 0;
        while (buffer.hasRemaining()) {
            var count = channel.read(buffer, offset);
            if (count < 0) {
                throw new IOException("open file ended before path size");
            }
            if (count == 0) {
                Thread.onSpinWait();
                continue;
            }
            offset += count;
        }
        if (channel.size() != expectedSize) {
            throw new IOException("open file changed while it was read");
        }
        return data;
    }
}
