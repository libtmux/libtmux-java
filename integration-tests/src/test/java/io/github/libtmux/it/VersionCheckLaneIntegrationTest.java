package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.SessionId;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.exception.UnsupportedFeatureException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The version check against the real binary of the lane. On a release below the floor every library call is
 * refused and a sentinel session, option and buffer made with the binary's own client stays as it
 * was; on a supported release the same calls work. The rejection lanes run only this class, so
 * nothing there is judged by a fake. The 3.0a and 3.1c lanes also put a newer client in front of the
 * old daemon, which is the only way their own answers reach the library.
 */
@Tag("version-check")
final class VersionCheckLaneIntegrationTest {

    private static final TmuxVersion FLOOR = TmuxVersion.MINIMUM;

    @Test
    void theBinaryOfTheLaneIsCheckedOrRefusedBeforeAnyEffect() throws Exception {
        String binary = System.getProperty("libtmux.tmux", "tmux");
        Path root = Files.createTempDirectory(Path.of("/tmp/libtmux-java-test"), "version-check-");
        Path socket = root.resolve("s");
        TmuxVersion running = versionOf(binary, socket);
        boolean supported = running.compareTo(FLOOR) >= 0;
        requireLaneBinary(System.getProperty("libtmux.tmux.expected"), running, "libtmux.tmux");
        String rejection = System.getProperty("libtmux.tmux.rejection");
        if (rejection != null) {
            assertEquals(
                    !Boolean.parseBoolean(rejection),
                    supported,
                    "the lane " + (Boolean.parseBoolean(rejection) ? "must be refused" : "must be supported")
                            + " but its binary reports " + running);
        }
        try {
            raw(binary, socket, "new-session", "-d", "-s", "sentinel");
            raw(binary, socket, "set-option", "-g", "@sentinel", "kept");
            raw(binary, socket, "set-buffer", "-b", "sentinel", "keep");

            ServerConfig config = ServerConfig.builder()
                    .binary(binary)
                    .endpoint(ServerEndpoint.socketPath(socket))
                    .configFile(Path.of("/dev/null"))
                    .build();
            try (Server server = Server.open(config)) {
                if (supported) {
                    server.requireCompatibleTmux();
                    assertTrue(server.cmd("set-option", "-g", "@sentinel", "changed")
                            .succeeded());
                    server.newSession("added");
                    assertEquals(List.of("changed"), raw(binary, socket, "show-options", "-gqv", "@sentinel"));
                    assertEquals(
                            List.of("added", "sentinel"),
                            raw(binary, socket, "list-sessions", "-F", "#{session_name}").stream()
                                    .sorted()
                                    .toList());
                    return;
                }
                assertThrows(UnsupportedFeatureException.class, server::requireCompatibleTmux);
                assertThrows(
                        UnsupportedFeatureException.class,
                        () -> server.cmd("set-option", "-g", "@sentinel", "changed"));
                assertThrows(
                        UnsupportedFeatureException.class,
                        () -> server.batch()
                                .add("set-option", "-g", "@sentinel", "changed")
                                .run());
                assertThrows(UnsupportedFeatureException.class, () -> server.newSession("intruder"));
                assertThrows(UnsupportedFeatureException.class, server::sessions);
                assertThrows(
                        UnsupportedFeatureException.class,
                        () -> server.buffers().delete("sentinel"));
                assertThrows(UnsupportedFeatureException.class, server::killServer);
            }
            assertEquals(List.of("kept"), raw(binary, socket, "show-options", "-gqv", "@sentinel"));
            assertEquals(List.of("keep"), raw(binary, socket, "show-buffer", "-b", "sentinel"));
            assertEquals(List.of("sentinel"), raw(binary, socket, "list-sessions", "-F", "#{session_name}"));
        } finally {
            try {
                raw(binary, socket, "kill-server");
            } catch (IOException | AssertionError gone) {
                // The server may already be gone.
            }
            Files.deleteIfExists(socket);
            Files.deleteIfExists(root);
        }
    }

    /**
     * A daemon from before 3.2 reached by a 3.3a-or-newer client: 3.1c rejects the probe with a usage
     * line and 3.0a refuses the client, and both leave the client's standard output open after it
     * exits. The refusal has to come back long before the command timeout and change nothing.
     */
    @Test
    void anOlderDaemonBehindANewerClientIsRefusedPromptly() throws Exception {
        String lane = System.getProperty("libtmux.tmux.expected", "");
        assumeTrue(lane.equals("3.0a") || lane.equals("3.1c"), "only the pre-3.2 lanes have such a daemon");
        String client = System.getProperty("libtmux.tmux.client");
        assertNotNull(client, "lane " + lane + " needs libtmux.tmux.client, a tmux of 3.3a or newer");
        String daemon = System.getProperty("libtmux.tmux", "tmux");
        Path root = Files.createTempDirectory(Path.of("/tmp/libtmux-java-test"), "old-daemon-");
        Path socket = root.resolve("s");
        TmuxVersion clientVersion = versionOf(client, socket);
        assertTrue(clientVersion.compareTo(FLOOR) >= 0, "the client of lane " + lane + " reports " + clientVersion);
        requireLaneBinary(System.getProperty("libtmux.tmux.client.expected"), clientVersion, "libtmux.tmux.client");
        try {
            raw(daemon, socket, "new-session", "-d", "-s", "sentinel");
            raw(daemon, socket, "set-option", "-g", "@sentinel", "kept");
            ServerConfig config = ServerConfig.builder()
                    .binary(client)
                    .endpoint(ServerEndpoint.socketPath(socket))
                    .configFile(Path.of("/dev/null"))
                    .defaultTimeout(Duration.ofSeconds(60))
                    .build();
            try (Server server = Server.open(config)) {
                Duration bound = Duration.ofSeconds(10);
                UnsupportedFeatureException refused = assertTimeoutPreemptively(
                        bound,
                        () -> assertThrows(
                                UnsupportedFeatureException.class,
                                () -> server.cmd("set-option", "-g", "@sentinel", "changed")));
                String message = String.valueOf(refused.getMessage());
                assertTrue(message.contains("older than 3.2"), message);
                assertTrue(message.contains("restart any running server"), message);
                assertTimeoutPreemptively(
                        bound,
                        () -> assertThrows(UnsupportedFeatureException.class, () -> server.newSession("intruder")));
                assertTimeoutPreemptively(
                        bound, () -> assertThrows(UnsupportedFeatureException.class, server::requireCompatibleTmux));
                // The fenced path, and a control attach, twice: a second attach in one JVM is where a
                // pipe the old daemon keeps open used to hold the call until its timeout.
                assertTimeoutPreemptively(
                        bound,
                        () -> assertThrows(
                                UnsupportedFeatureException.class,
                                () -> server.batch()
                                        .add("set-option", "-g", "@sentinel", "changed")
                                        .run()));
                for (int attempt = 0; attempt < 2; attempt++) {
                    assertTimeoutPreemptively(
                            bound,
                            () -> assertThrows(
                                    UnsupportedFeatureException.class,
                                    () -> ControlClient.attachUnfenced(
                                            config, new SessionId("$0"), Duration.ofSeconds(8))));
                }
            }
            assertEquals(List.of("kept"), raw(daemon, socket, "show-options", "-gqv", "@sentinel"));
            assertEquals(List.of("sentinel"), raw(daemon, socket, "list-sessions", "-F", "#{session_name}"));
        } finally {
            try {
                raw(daemon, socket, "kill-server");
            } catch (IOException | AssertionError gone) {
                // The server may already be gone.
            }
            Files.deleteIfExists(socket);
            Files.deleteIfExists(root);
        }
    }

    /**
     * A daemon the library already knows as 3.3a is replaced on the same socket by the lane's own
     * binary, which is below the floor. The cached version must not be trusted: the next call is refused as unsupported,
     * promptly and before any effect, on the plain, the fenced and the control paths.
     */
    @Test
    void aKnownDaemonReplacedByAnOlderOneIsRefusedWithoutAnEffect() throws Exception {
        String lane = System.getProperty("libtmux.tmux.expected", "");
        assumeTrue(
                "true".equals(System.getProperty("libtmux.tmux.rejection")), "only a rejection lane has an old daemon");
        String client = System.getProperty("libtmux.tmux.client");
        assertNotNull(client, "lane " + lane + " needs libtmux.tmux.client, a tmux of 3.3a or newer");
        String daemon = System.getProperty("libtmux.tmux", "tmux");
        Duration bound = Duration.ofSeconds(10);
        for (String path : List.of("cmd", "batch", "control", "server.control")) {
            Path root = Files.createTempDirectory(Path.of("/tmp/libtmux-java-test"), "replaced-daemon-");
            Path socket = root.resolve("s");
            ServerConfig config = ServerConfig.builder()
                    .binary(client)
                    .endpoint(ServerEndpoint.socketPath(socket))
                    .configFile(Path.of("/dev/null"))
                    .defaultTimeout(Duration.ofSeconds(60))
                    .build();
            try (Server server = Server.open(config)) {
                raw(client, socket, "new-session", "-d", "-s", "known");
                List<Session> known = server.sessions();
                assertEquals(1, known.size(), "the 3.3a daemon is known before it is replaced");
                raw(client, socket, "kill-server");
                for (int wait = 0; wait < 100 && Files.exists(socket); wait++) {
                    TimeUnit.MILLISECONDS.sleep(50);
                }
                raw(daemon, socket, "new-session", "-d", "-s", "sentinel");
                raw(daemon, socket, "set-option", "-g", "@sentinel", "kept");

                UnsupportedFeatureException refused = assertTimeoutPreemptively(
                        bound,
                        () -> assertThrows(UnsupportedFeatureException.class, () -> {
                            if (path.equals("cmd")) {
                                server.cmd("set-option", "-g", "@sentinel", "changed");
                            } else if (path.equals("batch")) {
                                server.batch()
                                        .add("set-option", "-g", "@sentinel", "changed")
                                        .run();
                            } else if (path.equals("control")) {
                                ControlClient.attachUnfenced(config, new SessionId("$0"), Duration.ofSeconds(8));
                            } else {
                                server.control(known.getFirst(), Duration.ofSeconds(8));
                            }
                        }));
                assertTrue(refused.belowFloor(), path + ": " + refused.getMessage());
                assertEquals(List.of("kept"), raw(daemon, socket, "show-options", "-gqv", "@sentinel"), path);
                assertEquals(List.of("sentinel"), raw(daemon, socket, "list-sessions", "-F", "#{session_name}"), path);
            } finally {
                try {
                    raw(daemon, socket, "kill-server");
                } catch (IOException | AssertionError gone) {
                    // The server may already be gone.
                }
                Files.deleteIfExists(socket);
                Files.deleteIfExists(root);
            }
        }
    }

    /**
     * The release a lane binary reports, which must be the one its lane names: a lane that resolved
     * another build would otherwise pass by taking the other branch of the check. {@code master} is
     * the one lane that is not a release, and reports a development build.
     */
    private static void requireLaneBinary(String expected, TmuxVersion running, String property) {
        if (expected == null) {
            return;
        }
        if (expected.equals("master")) {
            assertTrue(running.development(), property + " is the master lane but reports " + running);
        } else {
            assertEquals(TmuxVersion.parse(expected), running, property + " is not the build its lane names");
        }
    }

    private static TmuxVersion versionOf(String binary, Path socket) throws Exception {
        return TmuxVersion.parse(raw(binary, socket, "-V").getFirst().replaceFirst("^\\S+ ", ""));
    }

    /** The binary's own client, outside the library. */
    private static List<String> raw(String binary, Path socket, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(binary));
        if (!arguments[0].equals("-V")) {
            command.addAll(List.of("-S", socket.toString(), "-f", "/dev/null"));
        }
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), command.toString());
        if (process.exitValue() != 0) {
            throw new AssertionError(command + " exited " + process.exitValue() + ": " + output);
        }
        return output.isEmpty() ? List.of() : List.of(output.stripTrailing().split("\n"));
    }
}
