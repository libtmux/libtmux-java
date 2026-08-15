package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.ServerConfig;
import com.git_pull.libtmux.ServerEndpoint;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.TmuxEnvironment;
import com.git_pull.libtmux.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finding the server you are already inside, from what tmux told the pane.
 *
 * <p>The parsing is settled by unit tests. What this adds is the round trip: the variables are taken
 * from a pane tmux really started, and what they parse to has to address that same pane.
 */
@ExtendWith(TmuxExtension.class)
final class EnvironmentIntegrationTest {

    @Test
    void whatTmuxExportsIntoAPaneAddressesThatPane(Server server, @TempDir Path directory) throws Exception {
        Session session = server.sessions().get(0);
        Reported reported = reportedFrom(session.windows().get(0), directory.resolve("env"));

        TmuxEnvironment here = TmuxEnvironment.of(reported.exported()).orElseThrow();

        assertEquals(reported.pane().id(), here.pane().orElseThrow(), "TMUX_PANE names the pane it was read in");

        assertEquals(session.id(), here.session(), "TMUX names the session that pane is in");
        assertEquals(
                server.cmd("display-message", "-p", "#{socket_path}").stdout().get(0),
                here.socket().toString(),
                "and the socket the server is really listening on");
    }

    @Test
    void aServerOpenedFromThoseVariablesSeesTheSameSession(Server server, @TempDir Path directory) throws Exception {
        Session session = server.sessions().get(0);
        TmuxEnvironment here = TmuxEnvironment.of(reportedFrom(session.windows().get(0), directory.resolve("env"))
                        .exported())
                .orElseThrow();

        // The socket comes from the environment; the binary comes from the lane, because a matrix
        // lane's tmux is not the one on PATH and two builds should not share one socket.
        ServerConfig config = ServerConfig.builder()
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .endpoint(ServerEndpoint.socketPath(here.socket()))
                .build();

        try (Server reopened = Server.open(config)) {
            assertTrue(
                    reopened.sessions().stream().anyMatch(seen -> seen.id().equals(here.session())),
                    "the server reached through the environment does not hold the session it named");
        }
    }

    @Test
    void theServerPidNamesTheServerAndNotThePane(Server server, @TempDir Path directory) throws Exception {
        Reported reported = reportedFrom(server.sessions().get(0).windows().get(0), directory.resolve("env"));
        TmuxEnvironment here = TmuxEnvironment.of(reported.exported()).orElseThrow();

        assertEquals(
                Long.parseLong(reported.pane().expand("#{pid}")), here.serverPid(), "TMUX carries the server's pid");
        assertTrue(here.serverPid() != reported.pane().pid(), "which is not the process running in the pane");
    }

    /**
     * Makes a pane whose command reports the two variables tmux set for it.
     *
     * <p>The command is what tmux spawns, rather than keys sent to a shell. A pane's shell is not
     * ready the moment the pane exists, and keys sent before it is are simply lost — which is how
     * this arrived, as one lane in eight failing to hear back.
     */
    private static Reported reportedFrom(Window window, Path report) throws InterruptedException {
        Pane pane = window.split(
                s -> s.running("sh", "-c", "printf '%s\\n%s\\n' \"$TMUX\" \"$TMUX_PANE\" > " + report + "; sleep 30"));

        assertTrue(await(() -> lines(report).size() >= 2), "the pane never reported its environment");

        Map<String, String> exported = new HashMap<>();
        exported.put("TMUX", lines(report).get(0));
        exported.put("TMUX_PANE", lines(report).get(1));
        return new Reported(pane, exported);
    }

    /** A pane tmux started, and what tmux exported into it. */
    private record Reported(Pane pane, Map<String, String> exported) {}

    private static List<String> lines(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
