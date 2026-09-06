package io.github.libtmux.tools.mcpswap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

final class SwapLock implements AutoCloseable {
    private static final Set<java.nio.file.attribute.PosixFilePermission> DIRECTORY_MODE =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<java.nio.file.attribute.PosixFilePermission> FILE_MODE =
            PosixFilePermissions.fromString("rw-------");
    private static final Semaphore PROCESS_GATE = new Semaphore(1, true);
    private static final Set<String> ACTIVE_IDENTITIES = new java.util.HashSet<>();

    private final FileChannel channel;
    private final FileLock lock;
    private final FileSnapshot state;

    private SwapLock(FileChannel channel, FileLock lock, FileSnapshot state) {
        this.channel = channel;
        this.lock = lock;
        this.state = state;
    }

    static SwapLock acquire(Path home, Map<String, String> environment) throws IOException {
        PROCESS_GATE.acquireUninterruptibly();
        var transferred = false;
        try {
            var path = SwapPaths.lock(home, environment).toAbsolutePath().normalize();
            var parent = path.getParent();
            if (parent == null) {
                throw new IOException("swap lock has no parent");
            }
            secureDirectory(parent);
            try {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_MODE));
            } catch (FileAlreadyExistsException ignored) {
                // Validated below before the file is opened.
            }
            var before = FileSnapshot.capture(path);
            requireSafe(before, path);
            Set<OpenOption> options =
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            var channel = FileChannel.open(path, options);
            FileLock lock = null;
            try {
                try {
                    lock = channel.lock();
                } catch (OverlappingFileLockException busy) {
                    throw new IOException("another mcp-swap transaction is running", busy);
                }
                var tokenText = new StringBuilder(UUID.randomUUID().toString());
                if (tokenText.length() == before.size()) {
                    tokenText.append('.');
                }
                var token = tokenText.toString().getBytes(StandardCharsets.US_ASCII);
                var buffer = ByteBuffer.wrap(token);
                channel.truncate(0);
                channel.position(0);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                    // FileChannel may consume the buffer in more than one write.
                }
                channel.force(true);
                var state = FileSnapshot.capture(path, channel);
                requireSafe(state, path);
                if (!state.identity().equals(before.identity()) || !java.util.Arrays.equals(token, state.bytes())) {
                    throw new IOException("swap lock changed while it was acquired");
                }
                synchronized (SwapLock.class) {
                    ACTIVE_IDENTITIES.add(state.identity());
                }
                transferred = true;
                return new SwapLock(channel, lock, state);
            } catch (IOException | RuntimeException error) {
                if (lock != null) {
                    lock.close();
                }
                channel.close();
                throw error;
            }
        } finally {
            if (!transferred) {
                PROCESS_GATE.release();
            }
        }
    }

    static void preflight(Path lockPath) throws IOException {
        var path = lockPath.toAbsolutePath().normalize();
        var parent = path.getParent();
        if (parent == null) {
            throw new IOException("swap lock has no parent");
        }
        validateDirectories(parent);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            requireSafe(FileSnapshot.capture(path), path);
        }
    }

    Path path() {
        return state.path();
    }

    String identity() {
        return state.identity();
    }

    static synchronized void rejectAlias(String identity, Path path) throws IOException {
        if (ACTIVE_IDENTITIES.contains(identity)) {
            throw new IOException("transaction path aliases the active swap lock: " + path);
        }
    }

    void verify() throws IOException {
        if (!lock.isValid()) {
            throw new IOException("swap lock is no longer held");
        }
        state.verify(channel);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.close();
        } finally {
            try {
                channel.close();
            } finally {
                synchronized (SwapLock.class) {
                    ACTIVE_IDENTITIES.remove(state.identity());
                }
                PROCESS_GATE.release();
            }
        }
    }

    private static void secureDirectory(Path directory) throws IOException {
        if (directory == null) {
            throw new IOException("swap lock has no parent");
        }
        var missing = new java.util.ArrayDeque<Path>();
        var cursor = directory;
        while (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            missing.push(cursor);
            cursor = cursor.getParent();
            if (cursor == null) {
                throw new IOException("swap lock has no existing ancestor");
            }
        }
        while (!missing.isEmpty()) {
            var next = missing.pop();
            try {
                Files.createDirectory(next, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
            } catch (FileAlreadyExistsException ignored) {
                // A concurrent creator still has to pass the same validation.
            }
        }
        validateDirectories(directory);
    }

    private static void validateDirectories(Path directory) throws IOException {
        var checked = directory;
        for (int depth = 0; depth < 2; depth++) {
            if (Files.exists(checked, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(checked) || !Files.isDirectory(checked, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("unsafe swap lock directory: " + checked);
                }
                var permissions = Files.getPosixFilePermissions(checked, LinkOption.NOFOLLOW_LINKS);
                if (!permissions.equals(DIRECTORY_MODE)) {
                    throw new IOException("swap lock directory must have mode 0700: " + checked);
                }
            }
            checked = checked.getParent();
            if (checked == null) {
                throw new IOException("swap lock directory has no parent");
            }
        }
    }

    private static void requireSafe(FileSnapshot snapshot, Path path) throws IOException {
        if (!snapshot.exists()
                || snapshot.links() != 1
                || !snapshot.permissions().equals("rw-------")) {
            throw new IOException("swap lock must be a private unlinked regular file: " + path);
        }
    }
}
