package io.github.libtmux.tools.mcpswap;

final class FileContent {
    private final boolean exists;
    private final byte[] data;
    private final String permissions;

    private FileContent(boolean exists, byte[] data, String permissions) {
        this.exists = exists;
        this.data = data.clone();
        this.permissions = permissions;
    }

    static FileContent absent() {
        return new FileContent(false, new byte[0], "");
    }

    static FileContent of(byte[] data, String permissions) {
        return new FileContent(true, data, permissions);
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

    String digest() {
        return exists ? FileSnapshot.sha256(data) : "";
    }
}
