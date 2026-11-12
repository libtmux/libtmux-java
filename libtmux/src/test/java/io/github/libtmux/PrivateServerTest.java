package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.exception.ServerUnavailableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link Server#start}: a tmux server that belongs to the handle it returns. */
final class PrivateServerTest {

    private static final String TMUX = System.getProperty("libtmux.tmux", "tmux");
    private static final Path TEST_ROOT = Path.of("/tmp/libtmux-java-test");

    @TempDir
    Path scratch;

    private Path root;

    @BeforeEach
    void makeRoot() throws IOException {
        Files.createDirectories(TEST_ROOT);
        root = Files.createTempDirectory(TEST_ROOT, "p-");
    }

    @AfterEach
    void removeRoot() throws IOException {
        try (Stream<Path> walked = Files.walk(root)) {
            walked.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        }
    }

    private ServerLaunch.Builder launch() {
        return ServerLaunch.builder().binary(TMUX).root(root);
    }

    private static Path socketOf(Server server) {
        return ((ServerEndpoint.SocketPath) server.config().endpoint()).path();
    }

    private static Path directoryOf(Server server) {
        return java.util.Objects.requireNonNull(socketOf(server).getParent());
    }

    private static String messageOf(Throwable failure) {
        return String.valueOf(failure.getMessage());
    }

    private static long pidOf(Server server) {
        return Long.parseLong(
                server.cmd("display-message", "-p", "#{pid}").stdout().getFirst());
    }

    private long count(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children.count();
        }
    }

    @Test
    void aStartedServerAnswersAndCloseEndsItAndRemovesItsDirectory() throws Exception {
        Server server = Server.start(launch().build());
        Path directory = directoryOf(server);
        ProcessHandle daemon = ProcessHandle.of(pidOf(server)).orElseThrow();
        try {
            assertTrue(server.isAlive());
            assertTrue(Files.exists(socketOf(server)));
            assertTrue(directory.startsWith(root), "the directory is under the requested root");
            assertEquals(List.of(), server.sessions(), "the server starts with no session");
            assertEquals("work", server.newSession("work").name());
            assertTrue(daemon.isAlive());
        } finally {
            server.close();
        }
        assertFalse(daemon.isAlive(), "close left the daemon running");
        assertFalse(Files.exists(directory), "close left the directory behind");
        assertEquals(0, count(root));
    }

    @Test
    void closeIsIdempotent() {
        Server server = Server.start(launch().build());
        server.close();
        server.close();
    }

    @Test
    void theServerReadsAnEmptyConfigUnlessOneIsGiven() throws Exception {
        Path config = Files.writeString(scratch.resolve("tmux.conf"), "set-option -g @given yes\n");
        try (Server empty = Server.start(launch().build());
                Server given = Server.start(launch().configFile(config).build())) {
            assertEquals("", empty.globalOptions().get("@given").orElse(""));
            assertEquals("yes", given.globalOptions().get("@given").orElseThrow());
        }
    }

    @Test
    void aStartedServerKeepsNoSessionAliveForTheCaller() {
        try (Server server = Server.start(launch().build())) {
            server.newSession("only");
            server.killSession("only");
            assertTrue(server.isAlive(), "an owned server outlives its last session");
        }
    }

    @Test
    void closeFailsWhenTheServerStillAnswers() throws Exception {
        // The daemon the library started is a shell that runs tmux in the background and ignores
        // kill-server, so ending the process the library holds leaves a server answering.
        Path real = Path.of(realTmux());
        Path wrapper = Files.writeString(
                scratch.resolve("tmux-stubborn"),
                "#!/bin/sh\n"
                        + "for a in \"$@\"; do [ \"$a\" = kill-server ] && exit 0; done\n"
                        + "for a in \"$@\"; do\n"
                        + "  if [ \"$a\" = -D ]; then \"" + real + "\" \"$@\" & wait; exit; fi\n"
                        + "done\n"
                        + "exec \"" + real + "\" \"$@\"\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
        Server server = Server.start(launch().binary(wrapper.toString())
                .timeout(Duration.ofSeconds(2))
                .build());
        Path socket = socketOf(server);
        Path directory = socket.getParent();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, server::close);

            assertTrue(messageOf(failure).contains("still answers"), messageOf(failure));
            assertTrue(Files.exists(directory), "a directory a server still answers on must stay");
        } finally {
            run(real.toString(), "-S", socket.toString(), "kill-server");
        }
    }

    @Test
    void aServerTheProgramLeavesUnderTheOwnedDirectoryFailsCloseAndIsStopped() throws Exception {
        Server server = Server.start(launch().build());
        Path directory = directoryOf(server);
        Path nested = directory.resolve("nested.sock");
        run(realTmux(), "-S", nested.toString(), "-f", "/dev/null", "new-session", "-d", "-s", "leak", "cat");
        ProcessHandle leaked = serverProcess(nested);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, server::close);

            assertTrue(messageOf(failure).contains("left a tmux server running"), messageOf(failure));
            assertTrue(messageOf(failure).contains("nested.sock"), messageOf(failure));
            assertTrue(leaked.onExit().get(10, TimeUnit.SECONDS) != null);
            assertFalse(leaked.isAlive(), "the leaked server was not stopped");
            assertFalse(Files.exists(directory), "the directory was kept after the leak was stopped");
        } finally {
            leaked.destroyForcibly();
        }
    }

    @Test
    void aSocketNothingAnswersOnIsNotALeak() throws Exception {
        Server server = Server.start(launch().build());
        Path directory = directoryOf(server);
        Path stale = directory.resolve("stale.sock");
        run(realTmux(), "-S", stale.toString(), "-f", "/dev/null", "new-session", "-d", "-s", "x", "cat");
        ProcessHandle dead = serverProcess(stale);
        dead.destroyForcibly();
        dead.onExit().get(10, TimeUnit.SECONDS);
        assertTrue(Files.exists(stale), "the dead server left no socket file to ignore");

        server.close();

        assertFalse(Files.exists(directory));
    }

    @Test
    void theServerHasNoSelectorFromTheCallersEnvironment() {
        Map<String, String> caller = Map.of(
                "TMUX", "/decoy,1,0",
                "TMUX_PANE", "%3",
                "LIBTMUX_SOCKET", "decoy",
                "LIBTMUX_SOCKET_PATH", "/decoy.sock",
                "TMUX_TMPDIR", "/decoy-tmpdir",
                "PATH", "/usr/bin");

        Map<String, String> daemon = PrivateDaemon.environment(caller, Path.of("/owned/tmp"));

        assertEquals(new TreeSet<>(List.of("PATH", "TMUX_TMPDIR")), new TreeSet<>(daemon.keySet()));
        assertEquals("/owned/tmp", daemon.get("TMUX_TMPDIR"));
    }

    @Test
    void aCallerInsideTmuxStartsAnUnrelatedServer() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        Probe.class.getName(),
                        root.toString(),
                        TMUX)
                .redirectErrorStream(true);
        builder.environment()
                .putAll(Map.of(
                        "TMUX", "/decoy,1,0",
                        "TMUX_PANE", "%3",
                        "LIBTMUX_SOCKET", "decoy",
                        "LIBTMUX_SOCKET_PATH", "/decoy.sock"));
        Process child = builder.start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the probe did not exit");

        assertEquals("selectors=\n", output, output);
        assertEquals(0, child.exitValue());
    }

    @Test
    void anUnreadableBinaryFailsStartAndLeavesNothing() throws Exception {
        assertThrows(
                ServerUnavailableException.class,
                () -> Server.start(
                        launch().binary(scratch.resolve("absent").toString()).build()));
        assertEquals(0, count(root));
    }

    @Test
    void aServerThatExitsBeforeItIsReadyFailsStartAndLeavesNothing() throws Exception {
        Path exits = Files.writeString(scratch.resolve("tmux-exits"), "#!/bin/sh\nexit 3\n");
        Files.setPosixFilePermissions(exits, PosixFilePermissions.fromString("rwx------"));

        assertThrows(
                ServerUnavailableException.class,
                () -> Server.start(launch().binary(exits.toString()).build()));
        assertEquals(0, count(root));
    }

    @Test
    void anInterruptedStartLeavesNeitherADaemonNorADirectory() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThrows(RuntimeException.class, () -> Server.start(launch().build()));
        } finally {
            Thread.interrupted();
            List<ProcessHandle> survivors = ProcessHandle.allProcesses()
                    .filter(handle -> {
                        String[] argv = handle.info().arguments().orElse(new String[0]);
                        for (int index = 0; index + 1 < argv.length; index++) {
                            if ("-S".equals(argv[index]) && argv[index + 1].startsWith(root.toString())) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .toList();
            survivors.forEach(ProcessHandle::destroyForcibly);
            for (ProcessHandle survivor : survivors) {
                survivor.onExit().get(10, TimeUnit.SECONDS);
            }
            assertEquals(0, survivors.size(), "a daemon the failed start owned was left running");
        }
        assertEquals(0, count(root));
    }

    /** Starts a private server and reports which variables select a server in the daemon's environment. */
    public static final class Probe {
        public static void main(String[] args) {
            try (Server server = Server.start(ServerLaunch.builder()
                    .binary(args[1])
                    .root(Path.of(args[0]))
                    .build())) {
                List<String> names = new ArrayList<>(server.environment().all().keySet());
                names.removeIf(name ->
                        !(name.equals("TMUX") || name.equals("TMUX_PANE") || name.startsWith("LIBTMUX_SOCKET")));
                System.out.println("selectors=" + String.join(",", new TreeSet<>(names)));
            }
        }
    }

    private static String realTmux() {
        if (TMUX.contains("/")) return TMUX;
        for (String entry : System.getenv("PATH").split(":", -1)) {
            Path candidate = Path.of(entry, TMUX);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        throw new AssertionError("no tmux on PATH");
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor(30, TimeUnit.SECONDS);
    }

    private static ProcessHandle serverProcess(Path socket) {
        return ProcessHandle.allProcesses()
                .filter(handle -> {
                    String[] argv = handle.info().arguments().orElse(new String[0]);
                    for (int index = 0; index + 1 < argv.length; index++) {
                        if ("-S".equals(argv[index])
                                && argv[index + 1].equals(socket.toString())
                                && handle.info().command().orElse("").endsWith("tmux")) {
                            return true;
                        }
                    }
                    return false;
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("no tmux server on " + socket));
    }
}
