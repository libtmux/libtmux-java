package io.github.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Teardown that survives its own process being killed.
 *
 * <p>A finalizer — this extension's {@code afterEach}, or Python libtmux's {@code addfinalizer} —
 * runs only if the process lives long enough to run it. A test JVM killed outright leaves a tmux
 * server with nobody holding it, and once the host's temporary-file cleaner removes the directory
 * the socket cannot even be reached to kill it. Nineteen such servers were found on this machine.
 *
 * <p>So a later run reaps them, and it decides what to reap from the owning process rather than from
 * a registry the killed run never got to update. The owner's pid is in the socket path.
 */
final class AbandonedServerTest {

    private static Path socketFor(Path root, long owner) throws IOException {
        Path directory = Files.createDirectory(root.resolve("libtmux-" + owner + "-" + System.nanoTime()));
        return directory.resolve("s");
    }

    private static void startServer(Path socket) throws Exception {
        Path config = socket.resolveSibling("tmux.conf");
        Files.writeString(config, "");
        Process started = new ProcessBuilder(
                        System.getProperty("libtmux.tmux", "tmux"),
                        "-S",
                        socket.toString(),
                        "-f",
                        config.toString(),
                        "new-session",
                        "-d",
                        "-s",
                        "abandoned")
                .start();
        assertTrue(started.waitFor(30, TimeUnit.SECONDS), "tmux did not start");
        assertEquals(0, started.exitValue(), "tmux did not start");
    }

    private static boolean alive(Path socket) throws Exception {
        Process asked = new ProcessBuilder(
                        System.getProperty("libtmux.tmux", "tmux"), "-S", socket.toString(), "list-sessions")
                .start();
        return asked.waitFor(30, TimeUnit.SECONDS) && asked.exitValue() == 0;
    }

    /** A pid that is certainly not running: a process this test started and then waited out. */
    private static long deadPid() throws Exception {
        Process gone = new ProcessBuilder("true").start();
        gone.waitFor(30, TimeUnit.SECONDS);
        return gone.pid();
    }

    /** The tmux server listening on a socket, which outlives the client that started it. */
    private static Optional<ProcessHandle> serverOn(Path socket) {
        return ProcessHandle.allProcesses()
                .filter(handle -> handle.info()
                        .command()
                        .map(command -> Path.of(command).getFileName().toString())
                        .filter("tmux"::equals)
                        .isPresent())
                .filter(handle -> {
                    String[] argv = handle.info().arguments().orElse(new String[0]);
                    for (int index = 0; index + 1 < argv.length; index++) {
                        if ("-S".equals(argv[index]) && argv[index + 1].equals(socket.toString())) {
                            return true;
                        }
                    }
                    return false;
                })
                .findFirst();
    }

    @Test
    void aServerWhoseOwnerIsGoneIsReaped(@TempDir Path root) throws Exception {
        Path socket = socketFor(root, deadPid());
        startServer(socket);
        assertTrue(alive(socket), "the fixture for this test must actually be running");

        int reaped = TmuxExtension.reapAbandoned(root);

        assertEquals(1, reaped);
        assertFalse(alive(socket), "a server nobody owns must not outlive the sweep");
    }

    /** Asserted on the process, so a client's own startup cost cannot hide the window. */
    @Test
    void theSweepCountsServersThatEndedRatherThanSignalsItSent(@TempDir Path root) throws Exception {
        Path socket = socketFor(root, deadPid());
        startServer(socket);
        ProcessHandle server = serverOn(socket).orElseThrow(() -> new AssertionError("no server to reap"));

        int reaped = TmuxExtension.reapAbandoned(root);

        assertEquals(1, reaped);
        assertFalse(server.isAlive(), "the sweep counted a server it had only asked to stop");
    }

    /**
     * The case that makes this safe to run unconditionally. Gradle forks a test worker per module and
     * the tmux matrix runs eight lanes, so a sweep that reaped every server under the shared root
     * would kill the servers of runs that are still using them.
     */
    @Test
    void aServerWhoseOwnerIsStillRunningIsLeftAlone(@TempDir Path root) throws Exception {
        Path socket = socketFor(root, ProcessHandle.current().pid());
        startServer(socket);

        int reaped = TmuxExtension.reapAbandoned(root);

        assertEquals(0, reaped);
        assertTrue(alive(socket), "this JVM is still running, so this server is still owned");

        new ProcessBuilder(List.of(System.getProperty("libtmux.tmux", "tmux"), "-S", socket.toString(), "kill-server"))
                .start()
                .waitFor(30, TimeUnit.SECONDS);
    }

    /** A directory under the root that names no owner is not something this sweep may judge. */
    @Test
    void aSocketThatNamesNoOwnerIsLeftAlone(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("not-ours"));
        Path socket = directory.resolve("s");
        startServer(socket);

        int reaped = TmuxExtension.reapAbandoned(root);

        assertEquals(0, reaped);
        assertTrue(alive(socket));

        new ProcessBuilder(List.of(System.getProperty("libtmux.tmux", "tmux"), "-S", socket.toString(), "kill-server"))
                .start()
                .waitFor(30, TimeUnit.SECONDS);
    }
}
