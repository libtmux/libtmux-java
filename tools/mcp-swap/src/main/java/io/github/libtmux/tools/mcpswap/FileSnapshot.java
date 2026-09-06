package io.github.libtmux.tools.mcpswap;

import java.io.IOException;
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
        var posix = Files.readAttributes(normalized, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var rawLinks = Files.getAttribute(normalized, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        var links = rawLinks instanceof Number number ? number.intValue() : 0;
        var data = Files.readAllBytes(normalized);
        var after = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!String.valueOf(basic.fileKey()).equals(String.valueOf(after.fileKey()))
                || basic.size() != after.size()
                || !basic.lastModifiedTime().equals(after.lastModifiedTime())) {
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

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
