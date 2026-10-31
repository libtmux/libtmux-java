package io.github.libtmux.workspace.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which tmux server a command reaches when the caller names a socket, sets a variable, or says
 * nothing. A wrong answer here builds a workspace on a server nobody was looking at.
 */
final class SocketSelectionTest {

    private static final Map<String, String> INSIDE_TMUX =
            Map.of("TMUX", "/tmp/libtmux-java-test/inside/s,1,0", "TMUX_PANE", "%0");

    @TempDir
    Path directory;

    private record Result(int code, String out, String err) {}

    /** The endpoint a command resolves, from the arguments and an environment given outright. */
    private ServerEndpoint endpoint(Map<String, String> environment, String... arguments) {
        Main.Context context = new Main.Context(
                environment,
                directory,
                InputStream.nullInputStream(),
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream());
        return Execution.endpoint(
                context, Arguments.create().parseArgs(arguments).subcommand());
    }

    /**
     * Runs the CLI in this process, with the variables under test set and no other that selects a
     * server. tmux itself is a child of this JVM, so it sees the quarantine the build gave this
     * process, not the map handed to the CLI.
     */
    private Result invoke(Map<String, String> overrides, String... arguments) {
        // A rule broken here sends a command to the default server, so the quarantine is checked
        // before any command can reach one.
        requirePrivateDefaultSocket();
        Map<String, String> environment = new HashMap<>(System.getenv());
        for (String name : List.of("TMUX", "TMUX_PANE", "LIBTMUX_SOCKET", "LIBTMUX_SOCKET_PATH")) {
            environment.remove(name);
        }
        environment.put("HOME", directory.toString());
        environment.put("LIBTMUX_TEST_TMUX", System.getProperty("libtmux.tmux", "tmux"));
        environment.putAll(overrides);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = Main.run(arguments, environment, directory, InputStream.nullInputStream(), out, err);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Where the build quarantines every tmux server this suite starts. */
    private static final Path TEST_ROOT = Path.of("/tmp/libtmux-java-test");

    /**
     * Refuses a {@code TMUX_TMPDIR} that does not resolve to a directory below the test root. A bare
     * client under any other one reaches a server this build does not own, and these tests stop what
     * they find.
     */
    static void requireBelowTestRoot(@Nullable String tmuxTmpDir) {
        if (tmuxTmpDir == null || tmuxTmpDir.isEmpty()) {
            throw new AssertionError("the build did not quarantine TMUX_TMPDIR");
        }
        Path root;
        Path quarantine;
        try {
            root = TEST_ROOT.toRealPath();
            quarantine = Path.of(tmuxTmpDir).toRealPath();
        } catch (IOException unresolved) {
            throw new AssertionError("TMUX_TMPDIR or the test root does not resolve to a directory", unresolved);
        }
        assertTrue(
                quarantine.startsWith(root) && !quarantine.equals(root),
                "TMUX_TMPDIR is not below the test root, so a bare client would reach a server this build does not own");
    }

    private static void requirePrivateDefaultSocket() {
        requireBelowTestRoot(System.getenv("TMUX_TMPDIR"));
        assertNull(System.getenv("TMUX"), "the suite is running inside tmux, so a child could inherit it");
    }

    private Path source(String session) throws Exception {
        Path source = directory.resolve(session + ".yaml");
        Files.writeString(source, "session_name: " + session + "\nwindows:\n  - panes: [null]\n");
        return source;
    }

    private static Server serverOn(ServerEndpoint endpoint) {
        return Server.builder()
                .endpoint(endpoint)
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .configFile(Path.of("/dev/null"))
                .build();
    }

    /**
     * The guard is what stands between a failed assertion and the developer's own server, so it is
     * shown to refuse each way a {@code TMUX_TMPDIR} can point outside the test root, a link planted
     * inside it included, and to accept one below it.
     */
    @Test
    void theQuarantineGuardRefusesATmuxTmpDirOutsideTheTestRoot(@TempDir Path directory) throws Exception {
        Path escape = Files.createSymbolicLink(directory.resolve("escape"), Path.of("/usr"));
        Path missing = TEST_ROOT.resolve("never-created-" + System.nanoTime());

        assertThrows(AssertionError.class, () -> requireBelowTestRoot(null));
        for (String outside :
                List.of("", "/", "/tmp", "/usr", TEST_ROOT.toString(), escape.toString(), missing.toString())) {
            assertThrows(AssertionError.class, () -> requireBelowTestRoot(outside), outside);
        }

        Assumptions.assumeTrue(
                directory.toRealPath().startsWith(TEST_ROOT.toRealPath()), "the build puts temporary directories here");
        requireBelowTestRoot(directory.toString());
    }

    // ------------------------------------------------------------------------------- resolution

    /**
     * It used to be the {@code TMUX} socket, passed as {@code -S}. tmux follows {@code TMUX} on its
     * own when it is given no socket, so passing it as well only takes the choice from tmux.
     */
    @Test
    void withNoFlagAndNoVariableNoSocketIsPassedEvenInsideTmux() {
        assertEquals(ServerEndpoint.defaultSocket(), endpoint(Map.of(), "load", "w.yaml"));
        assertEquals(ServerEndpoint.defaultSocket(), endpoint(INSIDE_TMUX, "load", "w.yaml"));
        assertEquals(ServerEndpoint.defaultSocket(), endpoint(INSIDE_TMUX, "freeze"));
    }

    @Test
    void libtmuxSocketPathSelectsAServerByPathAndLibtmuxSocketByName() {
        assertEquals(
                List.of("-S", "/tmp/libtmux-java-test/env/s"),
                endpoint(Map.of("LIBTMUX_SOCKET_PATH", "/tmp/libtmux-java-test/env/s"), "load", "w.yaml")
                        .flags());
        assertEquals(
                List.of("-L", "work"),
                endpoint(Map.of("LIBTMUX_SOCKET", "work"), "freeze").flags());
    }

    @Test
    void thePathVariableWinsWhenBothAreSet() {
        Map<String, String> both =
                Map.of("LIBTMUX_SOCKET", "work", "LIBTMUX_SOCKET_PATH", "/tmp/libtmux-java-test/env/s");

        assertEquals(
                List.of("-S", "/tmp/libtmux-java-test/env/s"),
                endpoint(both, "load", "w.yaml").flags());
    }

    @Test
    void aVariableBeatsTheTmuxSocket() {
        Map<String, String> environment = new HashMap<>(INSIDE_TMUX);
        environment.put("LIBTMUX_SOCKET", "work");

        assertEquals(
                List.of("-L", "work"), endpoint(environment, "load", "w.yaml").flags());
    }

    @Test
    void aFlagBeatsEveryVariable() {
        Map<String, String> both =
                Map.of("LIBTMUX_SOCKET", "work", "LIBTMUX_SOCKET_PATH", "/tmp/libtmux-java-test/env/s");

        assertEquals(
                List.of("-S", "/tmp/libtmux-java-test/flag/s"),
                endpoint(both, "load", "w.yaml", "-S", "/tmp/libtmux-java-test/flag/s")
                        .flags());
        assertEquals(
                List.of("-L", "flagged"),
                endpoint(both, "load", "w.yaml", "-L", "flagged").flags());
        assertEquals(
                List.of("-S", "/tmp/libtmux-java-test/flag/s"),
                endpoint(both, "load", "w.yaml", "-L", "flagged", "-S", "/tmp/libtmux-java-test/flag/s")
                        .flags(),
                "-S still beats -L, as it did before any variable was read");
    }

    /** A socket name that is a path is the operator's mistake, so it is a usage error naming the variable. */
    @Test
    void aSocketVariableHoldingAPathIsAUsageErrorThatLeavesItsValueOut() {
        Main.Failure refused = assertThrows(
                Main.Failure.class, () -> endpoint(Map.of("LIBTMUX_SOCKET", "nested/distinctive"), "load", "w.yaml"));

        assertEquals(Machine.Code.USAGE, refused.code);
        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("LIBTMUX_SOCKET"), message);
        assertFalse(message.contains("distinctive"), message);
    }

    // ------------------------------------------------------------------------- against real tmux

    /** A handle on the default server, made only once the quarantine is known to hold. */
    private static Server privateDefaultServer() {
        requirePrivateDefaultSocket();
        return serverOn(ServerEndpoint.defaultSocket());
    }

    /**
     * Stops every server a test named, the default one included. A broken rule sends a command
     * somewhere else, and a server left running there outlives the test and its directory.
     */
    private static void stopAll(Server... servers) {
        RuntimeException failure = null;
        for (Server server : servers) {
            try (server) {
                if (server.isAlive()) server.killServer();
            } catch (RuntimeException stopping) {
                if (failure == null) failure = stopping;
                else failure.addSuppressed(stopping);
            }
        }
        if (failure != null) throw failure;
    }

    private static Map<String, String> inherited(Server server, Path socket) {
        return Map.of(
                "TMUX",
                socket + "," + server.expand("#{pid}") + ",0",
                "TMUX_PANE",
                server.panes().getFirst().id().value());
    }

    @Test
    void loadBuildsOnTheServerLibtmuxSocketPathNames() throws Exception {
        Path socket = directory.resolve("variable-path");
        Server stray = privateDefaultServer();
        Server named = serverOn(ServerEndpoint.socketPath(socket));
        try {
            Result result = invoke(
                    Map.of("LIBTMUX_SOCKET_PATH", socket.toString()),
                    "load",
                    source("by-path").toString(),
                    "-d",
                    "--json");

            assertEquals(0, result.code(), result.err());
            assertTrue(named.hasSession("by-path"), result.out());
        } finally {
            stopAll(named, stray);
        }
    }

    /** A name resolves under {@code TMUX_TMPDIR}, so the build's quarantine is what keeps this private. */
    @Test
    void loadBuildsOnTheServerLibtmuxSocketNames() throws Exception {
        String name = "ws-" + UUID.randomUUID().toString().substring(0, 8);
        Server stray = privateDefaultServer();
        Server named = serverOn(ServerEndpoint.namedSocket(name));
        try {
            Result result = invoke(
                    Map.of("LIBTMUX_SOCKET", name), "load", source("by-name").toString(), "-d", "--json");

            assertEquals(0, result.code(), result.err());
            assertTrue(named.hasSession("by-name"), result.out());
        } finally {
            stopAll(named, stray);
        }
    }

    @Test
    void loadWithNothingSetReachesTheServerTmuxPicksAndNoOther() throws Exception {
        Path elsewhere = directory.resolve("not-chosen");
        Server chosen = privateDefaultServer();
        Server other = serverOn(ServerEndpoint.socketPath(elsewhere));
        try {
            // An environment that only describes a pane on another server must not redirect it.
            Map<String, String> inside = Map.of("TMUX", elsewhere + ",1,0", "TMUX_PANE", "%0");
            Result result = invoke(inside, "load", source("by-default").toString(), "-d", "--json");

            assertEquals(0, result.code(), result.err());
            assertTrue(chosen.hasSession("by-default"), result.out());
            assertFalse(Files.exists(elsewhere), "the TMUX socket was started as a server");
        } finally {
            stopAll(chosen, other);
        }
    }

    @Test
    void aFlagStillBeatsTheVariablesAgainstRealTmux() throws Exception {
        Path flagged = directory.resolve("flagged");
        Path variable = directory.resolve("variable");
        Server stray = privateDefaultServer();
        Server flaggedServer = serverOn(ServerEndpoint.socketPath(flagged));
        Server variableServer = serverOn(ServerEndpoint.socketPath(variable));
        try {
            Result result = invoke(
                    Map.of("LIBTMUX_SOCKET_PATH", variable.toString()),
                    "load",
                    source("by-flag").toString(),
                    "-d",
                    "-S",
                    flagged.toString(),
                    "--json");

            assertEquals(0, result.code(), result.err());
            assertTrue(flaggedServer.hasSession("by-flag"), "the flagged socket was not used");
            assertFalse(Files.exists(variable), "the variable chose a server over the flag");
        } finally {
            stopAll(flaggedServer, variableServer, stray);
        }
    }

    /**
     * A variable beats {@code TMUX}, so with one naming another server an attached load or an
     * {@code --append} from inside tmux is refused before it builds. The same variable naming the
     * pane's own server is accepted.
     */
    @Test
    void aVariableNamingAnotherServerThanTheTmuxOneIsRefusedBeforeAnythingIsBuilt() throws Exception {
        Path paneSocket = directory.resolve("pane");
        Path otherSocket = directory.resolve("other");
        Server stray = privateDefaultServer();
        Server pane = serverOn(ServerEndpoint.socketPath(paneSocket));
        Server other = serverOn(ServerEndpoint.socketPath(otherSocket));
        try {
            pane.newSession("host");
            other.newSession("elsewhere");
            Map<String, String> environment = new HashMap<>(inherited(pane, paneSocket));
            environment.put("LIBTMUX_SOCKET_PATH", otherSocket.toString());
            Path refused = source("refused");

            Result attached = invoke(environment, "load", refused.toString());
            Result appended = invoke(environment, "load", refused.toString(), "--append", "--json");

            assertEquals(2, attached.code(), attached.toString());
            assertTrue(attached.err().contains("is not the server selected to load onto"), attached.err());
            assertEquals(2, appended.code(), appended.toString());
            assertTrue(appended.err().contains("does not match the inherited tmux daemon"), appended.err());
            assertEquals(1, pane.windows().size(), "the pane's server was built on");
            assertEquals(1, other.windows().size(), "the variable's server was built on");
            assertFalse(pane.hasSession("refused") || other.hasSession("refused"));

            environment.put("LIBTMUX_SOCKET_PATH", paneSocket.toString());
            Result accepted = invoke(environment, "load", source("accepted").toString(), "--append", "--json");

            assertEquals(0, accepted.code(), accepted.err());
            assertEquals(2, pane.windows().size());
            assertEquals(1, other.windows().size());
        } finally {
            stopAll(pane, other, stray);
        }
    }

    // ------------------------------------------------------------------------------------ shell

    /**
     * tmuxp reads neither variable and follows {@code TMUX} when handed no socket, so the socket a
     * variable chose has to reach it as the flag it understands, and a launch that chose none must
     * leave tmuxp to follow {@code TMUX}.
     */
    @Test
    void shellHandsTmuxpTheSocketAVariableChoseAndNothingElse() throws Exception {
        Path recorded = directory.resolve("tmuxp-arguments");
        Path python = recordingPython(recorded);
        Map<String, String> runtime = Map.of("TMUX_WORKSPACE_PYTHON", python.toString());

        assertEquals(List.of(), tmuxpSocketFlags(runtime, INSIDE_TMUX, recorded), "nothing chosen");

        Map<String, String> path = new HashMap<>(runtime);
        path.put("LIBTMUX_SOCKET_PATH", directory.resolve("shell-path").toString());
        path.putAll(INSIDE_TMUX);
        assertEquals(
                List.of("-S", directory.resolve("shell-path").toString()), tmuxpSocketFlags(path, Map.of(), recorded));

        Map<String, String> name = new HashMap<>(runtime);
        name.put("LIBTMUX_SOCKET", "shell-name");
        assertEquals(List.of("-L", "shell-name"), tmuxpSocketFlags(name, Map.of(), recorded));

        Map<String, String> flagged = new HashMap<>(path);
        assertEquals(
                List.of("-L", "flagged"),
                tmuxpSocketFlags(flagged, Map.of(), recorded, "-L", "flagged"),
                "a flag is handed over as given, and a variable adds nothing beside it");
    }

    private List<String> tmuxpSocketFlags(
            Map<String, String> environment, Map<String, String> extra, Path recorded, String... flags)
            throws Exception {
        Files.deleteIfExists(recorded);
        Map<String, String> combined = new HashMap<>(environment);
        combined.putAll(extra);
        List<String> arguments = new java.util.ArrayList<>(List.of("shell", "-c", "print(1)", "--json"));
        arguments.addAll(List.of(flags));
        Result result = invoke(combined, arguments.toArray(String[]::new));
        assertEquals(0, result.code(), result.err());
        List<String> seen = Files.readAllLines(recorded);
        List<String> socket = new java.util.ArrayList<>();
        for (int index = 0; index < seen.size(); index++) {
            if (seen.get(index).equals("-S") || seen.get(index).equals("-L")) {
                socket.add(seen.get(index));
                socket.add(seen.get(index + 1));
            }
        }
        return socket;
    }

    /** Answers the version probe, and writes the arguments of the run that follows it, one per line. */
    private Path recordingPython(Path recorded) throws Exception {
        Path python = directory.resolve("recording-python");
        Files.writeString(python, """
                #!/bin/sh
                case "$*" in *importlib.metadata*) printf '1.74.0\\n'; exit 0;; esac
                printf '%%s\\n' "$@" > '%s'
                """.formatted(recorded));
        Files.setPosixFilePermissions(python, PosixFilePermissions.fromString("rwxr-xr-x"));
        return python;
    }
}
