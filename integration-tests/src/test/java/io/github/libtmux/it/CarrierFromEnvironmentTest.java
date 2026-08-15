package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.ExecutionMode;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A carrier chosen for a program from outside it, proved by launching one.
 *
 * <p>Resolving {@code LIBTMUX_MODE} into a config is a unit test's job and is tested there. This
 * asks the harder question: does a program started with the variable really carry its commands that
 * way? So a JVM is launched with it and tmux is asked what attached — a control client reports
 * itself through {@code #{client_control_mode}}, on every release in the supported range.
 *
 * <p>The same program is launched again without the variable, and must attach nothing. Without that
 * arm the test would pass against a library that ignored the variable entirely.
 */
final class CarrierFromEnvironmentTest {

    private static final String TMUX = System.getProperty("libtmux.tmux", "tmux");

    /**
     * Longer than the launched program's own deadline, so that a stuck command fails as itself.
     *
     * <p>Its config takes the default 30-second timeout, and an error naming the tmux command that
     * timed out is worth more than this killing the JVM that would have reported it.
     */
    private static final long PATIENCE_SECONDS = 60;

    /** What a launched program did and said. */
    private record Launched(int status, List<String> said) {}

    @Test
    void aProgramLaunchedWithTheVariableCarriesItsCommandsThatWay(@TempDir Path directory) throws Exception {
        Path socket = directory.resolve("s");
        Path config = emptyConfig(directory);

        try (Server server = Server.open(hosting(socket, config))) {
            // Control mode attaches to a session, so there has to be one before a launched program
            // can do anything but fall back.
            server.newSession("host");

            Launched chosen = launch(socket, config, "control");
            Launched unset = launch(socket, config, null);

            assertEquals(0, chosen.status(), "the launched program failed: " + chosen.said());
            assertTrue(
                    chosen.said().contains("mode=CONTROL"),
                    "the launched program did not take the carrier: " + chosen.said());
            assertTrue(
                    chosen.said().contains("control-clients=1"),
                    "the launched program reported a carrier it did not use: " + chosen.said());

            assertEquals(0, unset.status(), "the launched program failed: " + unset.said());
            assertTrue(unset.said().contains("mode=DIRECT"), "an unset variable chose a carrier: " + unset.said());
            assertTrue(
                    unset.said().contains("control-clients="),
                    "something attached a control client without being asked: " + unset.said());

            server.killServer();
        }
    }

    @Test
    void aProgramLaunchedWithAMisspelledVariableSaysSoAndStops(@TempDir Path directory) throws Exception {
        Path socket = directory.resolve("s");
        Path config = emptyConfig(directory);

        try (Server server = Server.open(hosting(socket, config))) {
            server.newSession("host");

            Launched refused = launch(socket, config, "contro");

            assertNotEquals(0, refused.status(), "a misspelled carrier was survivable: " + refused.said());
            assertTrue(
                    refused.said().stream().anyMatch(line -> line.contains(ExecutionMode.VARIABLE)),
                    "a misspelled carrier has to name what to fix: " + refused.said());
            assertTrue(
                    refused.said().stream().noneMatch(line -> line.startsWith("mode=")),
                    "a misspelled carrier ran anyway, on whichever one it fell back to: " + refused.said());

            server.killServer();
        }
    }

    /**
     * The tmux server the launched programs talk to.
     *
     * <p>Its carrier is named, so that the only control client tmux can report is a launched
     * program's. Left unsaid, this fixture would take the carrier of whoever ran the suite, and the
     * arm proving nothing attaches would be watching its own host.
     */
    private static ServerConfig hosting(Path socket, Path config) {
        return ServerConfig.builder()
                .binary(TMUX)
                .endpoint(ServerEndpoint.socketPath(socket))
                .configFile(config)
                .mode(ExecutionMode.DIRECT)
                .build();
    }

    private static Path emptyConfig(Path directory) throws IOException {
        Path config = directory.resolve("empty.conf");
        Files.writeString(config, "");
        return config;
    }

    /**
     * Runs {@link Probe} in a JVM of its own and returns everything it said.
     *
     * <p>A launched program is the only way to test this honestly: a variable cannot be set for a
     * process that is already running, so a test that set one would be testing something else.
     */
    private static Launched launch(Path socket, Path config, @Nullable String mode) throws Exception {
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath",
                System.getProperty("java.class.path"),
                Probe.class.getName(),
                TMUX,
                socket.toString(),
                config.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        // Whatever tmux this test is itself running inside is not what the probe is asking about.
        builder.environment().remove("TMUX");
        builder.environment().remove("TMUX_PANE");
        if (mode == null) {
            builder.environment().remove(ExecutionMode.VARIABLE);
        } else {
            builder.environment().put(ExecutionMode.VARIABLE, mode);
        }

        // Written to a file rather than read from a pipe. Draining a pipe first would park this
        // thread in read() with no deadline, where the wait below could never be reached — a
        // launched program that hung would hang the suite instead of failing it.
        Path said = socket.resolveSibling("said-" + (mode == null ? "unset" : mode));
        builder.redirectOutput(said.toFile());

        Process process = builder.start();
        if (!process.waitFor(PATIENCE_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("the launched program never finished; it said " + Files.readAllLines(said));
        }
        return new Launched(process.exitValue(), Files.readAllLines(said));
    }

    /**
     * A program that names no carrier, so that whatever it was launched with chooses one.
     *
     * <p>It asks tmux which clients are attached rather than reporting its own configuration, so
     * what it prints is tmux's answer and not the library agreeing with itself.
     */
    static final class Probe {

        public static void main(String[] args) throws IOException {
            ServerConfig config = ServerConfig.builder()
                    .binary(args[0])
                    .endpoint(ServerEndpoint.socketPath(Path.of(args[1])))
                    .configFile(Path.of(args[2]))
                    .build();

            try (Server server = Server.open(config)) {
                // Makes the carrier do something, which is what attaches a control client.
                if (server.sessions().size() != 1) {
                    throw new IllegalStateException("the host session is not there");
                }

                List<String> attached = server.cmd("list-clients", "-F", "#{client_control_mode}")
                        .stdout();
                System.out.println("mode=" + config.mode());
                System.out.println("control-clients=" + String.join(",", attached));
            }
        }
    }
}
