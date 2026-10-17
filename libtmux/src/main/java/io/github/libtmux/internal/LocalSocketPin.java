package io.github.libtmux.internal;

import io.github.libtmux.transport.ControlCarrier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A pin created only by carriers that own this filesystem. */
public final class LocalSocketPin implements ControlCarrier.PinnedSocket {
    private final Path directory;
    private final Path alias;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LocalSocketPin(Path directory, Path alias) {
        this.directory = directory;
        this.alias = alias;
    }

    public static LocalSocketPin retain(String socketPath) throws IOException {
        Path socket = Path.of(socketPath).toAbsolutePath();
        Map<String, Object> original = socketAttributes(socket);
        List<Path> parents = new ArrayList<>();
        parents.add(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath());
        Path peer = Objects.requireNonNull(socket.getParent());
        if (!parents.contains(peer)) parents.add(peer);
        IOException rejected = new IOException("cannot retain a short Unix socket alias on the same filesystem");
        for (Path parent : parents) {
            try {
                return retainAt(parent, socket, original);
            } catch (IOException failure) {
                rejected.addSuppressed(failure);
            }
        }
        throw rejected;
    }

    private static LocalSocketPin retainAt(Path parent, Path socket, Map<String, Object> original) throws IOException {
        Path directory = Files.createTempDirectory(
                parent, ".lc-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path alias = directory.resolve("s");
        try {
            // Darwin allows 103 pathname bytes; Linux's limit is slightly larger.
            if (alias.toString().getBytes(StandardCharsets.UTF_8).length > 103) {
                throw new IOException("the retained socket alias exceeds the Unix pathname limit");
            }
            Files.createLink(alias, socket);
            Map<String, Object> pinned = socketAttributes(alias);
            if (!Objects.equals(original.get("dev"), pinned.get("dev"))
                    || !Objects.equals(original.get("ino"), pinned.get("ino"))
                    || !Files.isSameFile(socket, alias)) {
                throw new IOException("the tmux socket changed while it was being retained");
            }
            return new LocalSocketPin(directory, alias);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(alias);
                Files.deleteIfExists(directory);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static Map<String, Object> socketAttributes(Path path) throws IOException {
        Map<String, Object> attributes = Files.readAttributes(path, "unix:mode,dev,ino", LinkOption.NOFOLLOW_LINKS);
        if (!(attributes.get("mode") instanceof Number mode)
                || (mode.intValue() & 0xF000) != 0xC000
                || attributes.get("dev") == null
                || attributes.get("ino") == null) {
            throw new IOException("control acquisition requires a Unix socket with a stable inode");
        }
        return attributes;
    }

    @Override
    public String socketPath() {
        return alias.toString();
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        Files.deleteIfExists(alias);
        Files.deleteIfExists(directory);
    }
}
