package io.github.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

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

    private static Path testRoot() throws IOException {
        Files.createDirectories(TmuxExtension.fixtureRoot());
        return Files.createTempDirectory(TmuxExtension.fixtureRoot(), "reaper-");
    }

    private static Path socketFor(Path root, long owner) throws IOException {
        Path directory = Files.createDirectory(root.resolve("libtmux-" + owner + "-" + System.nanoTime()));
        return directory.resolve("s");
    }

    /** Names a directory the way a JVM that knows its start does: pid, then start, then a suffix. */
    private static Path socketFor(Path root, long owner, String start) throws IOException {
        Path directory =
                Files.createDirectory(root.resolve("libtmux-" + owner + "-" + start + "-" + System.nanoTime()));
        return directory.resolve("s");
    }

    private static Path socketFor(Path root, long owner, long startMillis) throws IOException {
        return socketFor(root, owner, Long.toString(startMillis));
    }

    /** This JVM's start as the extension records it. */
    private static String thisStart() {
        return TmuxExtension.startOf(ProcessHandle.current().pid())
                .orElseThrow(() -> new AssertionError("this platform reports no start"));
    }

    /** A start this JVM did not have: an earlier tick, or an instant well past any clock drift. */
    private static String someOtherStart() {
        String start = thisStart();
        return start.startsWith("k")
                ? "k" + (Long.parseLong(start.substring(1)) - 1)
                : Long.toString(Long.parseLong(start) - Duration.ofMinutes(10).toMillis());
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

    private static void cleanup(Path root, Path socket) throws Exception {
        Optional<ProcessHandle> running = serverOn(socket);
        if (running.isPresent()) {
            ProcessHandle server = running.orElseThrow();
            server.destroy();
            try {
                server.onExit().get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                server.destroyForcibly();
                server.onExit().get(30, TimeUnit.SECONDS);
            }
        }
        TmuxExtension.deleteTree(root);
    }

    @Test
    void aServerWhoseOwnerIsGoneIsReaped() throws Exception {
        Path root = testRoot();
        Path socket = socketFor(root, deadPid());
        try {
            startServer(socket);
            assertTrue(alive(socket), "the fixture for this test must actually be running");

            int reaped = TmuxExtension.reapAbandoned(root);

            assertEquals(1, reaped);
            assertFalse(alive(socket), "a server nobody owns must not outlive the sweep");
            assertFalse(Files.exists(socket.getParent()), "the abandoned fixture directory was left behind");
        } finally {
            cleanup(root, socket);
        }
    }

    /** Asserted on the process, so a client's own startup cost cannot hide the window. */
    @Test
    void theSweepCountsServersThatEndedRatherThanSignalsItSent() throws Exception {
        Path root = testRoot();
        Path socket = socketFor(root, deadPid());
        try {
            startServer(socket);
            ProcessHandle server = serverOn(socket).orElseThrow(() -> new AssertionError("no server to reap"));

            int reaped = TmuxExtension.reapAbandoned(root);

            assertEquals(1, reaped);
            assertFalse(server.isAlive(), "the sweep counted a server it had only asked to stop");
        } finally {
            cleanup(root, socket);
        }
    }

    /**
     * The case that makes this safe to run unconditionally. Gradle forks a test worker per module and
     * the tmux matrix runs eight lanes, so a sweep that reaped every server under the shared root
     * would kill the servers of runs that are still using them.
     */
    @Test
    void aServerWhoseOwnerIsStillRunningIsLeftAlone() throws Exception {
        Path root = testRoot();
        Path socket = socketFor(root, ProcessHandle.current().pid(), thisStart());
        try {
            startServer(socket);

            int reaped = TmuxExtension.reapAbandoned(root);

            assertEquals(0, reaped);
            assertTrue(alive(socket), "this JVM is still running, so this server is still owned");
        } finally {
            cleanup(root, socket);
        }
    }

    /**
     * The pid alone is not proof of ownership: this JVM is alive, but a directory that recorded a
     * different start for it names a process that already exited and whose pid this one only
     * happens to now hold.
     */
    @Test
    void aServerWhoseOwnerPidWasReusedIsReaped() throws Exception {
        Path root = testRoot();
        Path socket = socketFor(root, ProcessHandle.current().pid(), someOtherStart());
        try {
            startServer(socket);
            assertTrue(alive(socket), "the fixture for this test must actually be running");

            int reaped = TmuxExtension.reapAbandoned(root);

            assertEquals(1, reaped);
            assertFalse(alive(socket), "a directory naming a stale start instant for a live pid must be reaped");
        } finally {
            cleanup(root, socket);
        }
    }

    /**
     * Two JVMs need not agree on when a third started. Linux reports a start as ticks since boot, and
     * the JDK turns that into an instant with the boot time it read once at its own start. That boot
     * time moves whenever the wall clock is stepped, so a sweep in a JVM started a few seconds later
     * can compute a different instant for a run that is still going.
     */
    @Test
    void aStartThatDriftedBySecondsStillNamesItsOwner() throws Exception {
        Path root = testRoot();
        long thisPid = ProcessHandle.current().pid();
        long thisStart = ProcessHandle.current()
                .info()
                .startInstant()
                .orElseThrow(() -> new AssertionError("this platform reports no start instant"))
                .toEpochMilli();
        Path socket = socketFor(root, thisPid, thisStart + 3_000);
        try {
            startServer(socket);

            int reaped = TmuxExtension.reapAbandoned(root);

            assertEquals(0, reaped);
            assertTrue(alive(socket), "a live run's server was reaped over a few seconds of clock drift");
        } finally {
            cleanup(root, socket);
        }
    }

    /** A directory under the root that names no owner is not something this sweep may judge. */
    @Test
    void aSocketThatNamesNoOwnerIsLeftAlone() throws Exception {
        Path root = testRoot();
        Path directory = Files.createDirectory(root.resolve("not-ours"));
        Path socket = directory.resolve("s");
        try {
            startServer(socket);

            int reaped = TmuxExtension.reapAbandoned(root);

            assertEquals(0, reaped);
            assertTrue(alive(socket));
        } finally {
            cleanup(root, socket);
        }
    }
}
