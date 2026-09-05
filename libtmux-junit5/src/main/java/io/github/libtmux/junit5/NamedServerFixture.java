package io.github.libtmux.junit5;

import io.github.libtmux.Server;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns the safe teardown of a tmux server addressed by socket name. */
public final class NamedServerFixture implements AutoCloseable {

    private static final int UNIX_FILE_TYPE = 0170000;
    private static final int UNIX_SOCKET = 0140000;
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(10);

    private final Server server;
    private final ProcessHandle process;
    private final Path quarantine;
    private final Path socket;
    private final Object fileKey;
    private boolean closed;

    private NamedServerFixture(Server server, ProcessHandle process, Path quarantine, Path socket, Object fileKey) {
        this.server = server;
        this.process = process;
        this.quarantine = quarantine;
        this.socket = socket;
        this.fileKey = fileKey;
    }

    /**
     * Authenticates a live named server and takes responsibility for ending it.
     *
     * @param server the server this test started
     * @param expectedName the exact socket name the test supplied to tmux
     * @param quarantine the {@code TMUX_TMPDIR} assigned to this test task
     * @return cleanup bound to the reported process, path, and socket inode
     * @throws IOException if the endpoint cannot be inspected
     */
    public static NamedServerFixture own(Server server, String expectedName, Path quarantine) throws IOException {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(expectedName, "expectedName");
        Objects.requireNonNull(quarantine, "quarantine");
        try {
            return authenticate(server, expectedName, quarantine);
        } catch (IOException | RuntimeException | AssertionError failure) {
            try {
                server.killServer();
            } catch (RuntimeException killFailure) {
                failure.addSuppressed(killFailure);
            }
            throw failure;
        }
    }

    private static NamedServerFixture authenticate(Server server, String expectedName, Path configuredQuarantine)
            throws IOException {
        List<String> identity =
                server.cmd("display-message", "-p", "#{pid}\t#{socket_path}").stdout();
        require(identity.size() == 1, "tmux reported identity rows " + identity);

        String row = identity.getFirst();
        String[] fields = row.split("\t", -1);
        require(fields.length == 2, "tmux reported a malformed identity row " + row);
        ProcessHandle process = ProcessHandle.of(Long.parseLong(fields[0]))
                .orElseThrow(() -> new AssertionError("the reported tmux process is already gone"));

        Path normalizedQuarantine = configuredQuarantine.toAbsolutePath().normalize();
        Path quarantine = normalizedQuarantine.toRealPath();
        require(
                normalizedQuarantine.equals(quarantine),
                "refusing to reclaim a socket through a linked quarantine path");
        Path reported = Path.of(fields[1]).toAbsolutePath().normalize();
        require(!Files.isSymbolicLink(reported), "refusing to reclaim a socket through a symbolic link");
        Path socket = reported.toRealPath();
        require(reported.equals(socket), "refusing to reclaim a socket through a linked directory");
        require(
                !socket.equals(quarantine) && socket.startsWith(quarantine),
                "refusing to reclaim a socket outside this port's quarantine");
        require(
                expectedName.equals(socket.getFileName().toString()),
                "tmux reported another server's socket " + socket);
        BasicFileAttributes owned = Files.readAttributes(socket, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(isUnixSocket(socket), "tmux did not report a unix-domain socket inode");
        require(owned.fileKey() != null, "the filesystem cannot identify the socket inode");
        return new NamedServerFixture(server, process, quarantine, socket, owned.fileKey());
    }

    /** Returns the exact socket path authenticated when ownership was taken. */
    public Path socket() {
        return socket;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }

        RuntimeException killFailure = null;
        try {
            server.killServer();
        } catch (RuntimeException failure) {
            killFailure = failure;
        }
        if (!awaitExit(process)) {
            AssertionError failure =
                    new AssertionError("the named tmux server did not exit; leaving " + socket + " in place");
            if (killFailure != null) {
                failure.addSuppressed(killFailure);
            }
            throw failure;
        }

        reclaimSocket();
        pruneEmptyParents(Objects.requireNonNull(socket.getParent()), quarantine);
        closed = true;
    }

    private void reclaimSocket() throws IOException {
        Path currentQuarantine = quarantine.toRealPath();
        require(
                currentQuarantine.equals(quarantine),
                "refusing to reclaim a socket through a replaced quarantine path");
        if (Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(socket), "refusing to remove a symbolic-link replacement");
            require(socket.equals(socket.toRealPath()), "refusing to remove a socket through a linked directory");
            require(
                    !socket.equals(quarantine) && socket.startsWith(quarantine),
                    "refusing to remove a socket outside this port's quarantine");
            BasicFileAttributes stale =
                    Files.readAttributes(socket, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            require(isUnixSocket(socket), "refusing to remove a non-socket replacement");
            require(fileKey.equals(stale.fileKey()), "refusing to remove a replacement socket inode");
            Files.delete(socket);
        }
        require(Files.notExists(socket, LinkOption.NOFOLLOW_LINKS), "the named tmux socket was not reclaimed");
    }

    private static boolean awaitExit(ProcessHandle process) {
        try {
            process.onExit().get(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return !process.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return !process.isAlive();
        }
    }

    private static boolean isUnixSocket(Path path) throws IOException {
        int mode = (Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        return (mode & UNIX_FILE_TYPE) == UNIX_SOCKET;
    }

    private static void pruneEmptyParents(Path directory, Path quarantine) throws IOException {
        Path candidate = directory;
        while (candidate != null && !candidate.equals(quarantine)) {
            require(candidate.startsWith(quarantine), "refusing to prune outside the tmux quarantine");
            if (Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                candidate = candidate.getParent();
                continue;
            }
            require(
                    Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS),
                    "refusing to prune a non-directory from the tmux quarantine");
            try {
                Files.delete(candidate);
            } catch (DirectoryNotEmptyException e) {
                return;
            }
            candidate = candidate.getParent();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
