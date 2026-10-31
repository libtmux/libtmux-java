package io.github.libtmux.workspace.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private static void requirePrivateDefaultSocket() {
        String quarantine = System.getenv("TMUX_TMPDIR");
        assertTrue(quarantine != null && !quarantine.isEmpty(), "the build did not quarantine TMUX_TMPDIR");
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

    @Test
    void loadBuildsOnTheServerLibtmuxSocketPathNames() throws Exception {
        Path socket = directory.resolve("variable-path");
        Result result = invoke(
                Map.of("LIBTMUX_SOCKET_PATH", socket.toString()),
                "load",
                source("by-path").toString(),
                "-d",
                "--json");

        assertEquals(0, result.code(), result.err());
        try (Server server = serverOn(ServerEndpoint.socketPath(socket))) {
            try {
                assertTrue(server.hasSession("by-path"), result.out());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** A name resolves under {@code TMUX_TMPDIR}, so the build's quarantine is what keeps this private. */
    @Test
    void loadBuildsOnTheServerLibtmuxSocketNames() throws Exception {
        requirePrivateDefaultSocket();
        String name = "ws-" + UUID.randomUUID().toString().substring(0, 8);
        Result result =
                invoke(Map.of("LIBTMUX_SOCKET", name), "load", source("by-name").toString(), "-d", "--json");

        assertEquals(0, result.code(), result.err());
        try (Server server = serverOn(ServerEndpoint.namedSocket(name))) {
            try {
                assertTrue(server.hasSession("by-name"), result.out());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void loadWithNothingSetReachesTheServerTmuxPicksAndNoOther() throws Exception {
        requirePrivateDefaultSocket();
        Path elsewhere = directory.resolve("not-chosen");
        try (Server chosen = serverOn(ServerEndpoint.defaultSocket());
                Server other = serverOn(ServerEndpoint.socketPath(elsewhere))) {
            try {
                // An environment that only describes a pane on another server must not redirect it.
                Map<String, String> inside = Map.of("TMUX", elsewhere + ",1,0", "TMUX_PANE", "%0");
                Result result = invoke(inside, "load", source("by-default").toString(), "-d", "--json");

                assertEquals(0, result.code(), result.err());
                assertTrue(chosen.hasSession("by-default"), result.out());
                assertFalse(Files.exists(elsewhere), "the TMUX socket was started as a server");
            } finally {
                if (chosen.isAlive()) chosen.killServer();
                if (other.isAlive()) other.killServer();
            }
        }
    }

    @Test
    void aFlagStillBeatsTheVariablesAgainstRealTmux() throws Exception {
        Path flagged = directory.resolve("flagged");
        Path variable = directory.resolve("variable");
        Result result = invoke(
                Map.of("LIBTMUX_SOCKET_PATH", variable.toString()),
                "load",
                source("by-flag").toString(),
                "-d",
                "-S",
                flagged.toString(),
                "--json");

        assertEquals(0, result.code(), result.err());
        assertTrue(Files.exists(flagged), "the flagged socket was not used");
        assertFalse(Files.exists(variable), "the variable chose a server over the flag");
        try (Server server = serverOn(ServerEndpoint.socketPath(flagged))) {
            if (server.isAlive()) server.killServer();
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
