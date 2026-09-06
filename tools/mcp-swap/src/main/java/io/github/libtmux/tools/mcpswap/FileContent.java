package io.github.libtmux.tools.mcpswap;

final class FileContent {
    private final boolean exists;
    private final byte[] data;
    private final String permissions;
    private final java.util.Optional<FileSnapshot> linkSource;

    private FileContent(boolean exists, byte[] data, String permissions, java.util.Optional<FileSnapshot> linkSource) {
        this.exists = exists;
        this.data = data.clone();
        this.permissions = permissions;
        this.linkSource = linkSource;
    }

    static FileContent absent() {
        return new FileContent(false, new byte[0], "", java.util.Optional.empty());
    }

    static FileContent of(byte[] data, String permissions) {
        return new FileContent(true, data, permissions, java.util.Optional.empty());
    }

    static FileContent linked(FileSnapshot source) {
        if (!source.exists()) {
            throw new IllegalArgumentException("link source is absent");
        }
        return new FileContent(true, source.bytes(), source.permissions(), java.util.Optional.of(source));
    }

    byte[] bytes() {
        return data.clone();
    }

    boolean exists() {
        return exists;
    }

    String permissions() {
        return permissions;
    }

    java.util.Optional<FileSnapshot> linkSource() {
        return linkSource;
    }

    String digest() {
        return exists ? FileSnapshot.sha256(data) : "";
    }
}
