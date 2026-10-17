package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.exception.UnsupportedFeatureException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The version check against the real binary of the lane. On a release below the floor every library call is
 * refused and a sentinel session, option and buffer made with the binary's own client stays as it
 * was; on a supported release the same calls work. The rejection lanes (3.2a and 3.3) run only this
 * class, so nothing there is judged by a fake.
 */
@Tag("version-check")
final class VersionCheckLaneIntegrationTest {

    private static final TmuxVersion FLOOR = new TmuxVersion(3, 3, "a");

    @Test
    void theBinaryOfTheLaneIsCheckedOrRefusedBeforeAnyEffect() throws Exception {
        String binary = System.getProperty("libtmux.tmux", "tmux");
        Path root = Files.createTempDirectory(Path.of("/tmp/libtmux-java-test"), "version-check-");
        Path socket = root.resolve("s");
        TmuxVersion running =
                TmuxVersion.parse(raw(binary, socket, "-V").getFirst().replaceFirst("^\\S+ ", ""));
        boolean supported = running.compareTo(FLOOR) >= 0;
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
