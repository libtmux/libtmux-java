package io.github.libtmux.examples;

import io.github.libtmux.Pane;
import io.github.libtmux.PaneRun;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Runs a command in a pane and reads what it exited with, rather than what the screen shows.
 *
 * <pre>{@code
 * java RunACommand.java /tmp/libtmux-java-dev/demo/s 'make test'
 * }</pre>
 */
public final class RunACommand {

    private RunACommand() {}

    public static void main(String[] args) throws InterruptedException {
        Path socket = Path.of(args.length > 0 ? args[0] : "/tmp/libtmux-java-dev/demo/s");
        String command = args.length > 1 ? args[1] : "printf 'built\\n'";
        System.out.println(run(socket, command));
    }

    /**
     * Separated from {@code main} so the suite can run exactly what a reader runs.
     *
     * <p>{@code InterruptedException} is declared rather than swallowed: the wait is tmux's, and
     * cancelling the thread that is waiting is how a caller stops waiting for it.
     */
    public static String run(Path socket, String command) throws InterruptedException {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        try (Server server = Server.open(config)) {
            Pane pane = server.panes().get(0);

            // The status is the shell's and the wait is tmux's own: nothing here is inferred from
            // what the pane happens to be showing, and the output is the command's alone — not the
            // line that was typed, not the prompt after it.
            PaneRun ran = pane.run(command, Duration.ofMinutes(2));

            return switch (ran.outcome()) {
                case FINISHED ->
                    "exit %d, %d line(s): %s"
                            .formatted(
                                    ran.exitStatus().orElseThrow(),
                                    ran.output().size(),
                                    String.join(" / ", ran.output()));
                // Still running at the deadline. What it printed so far is worth having, and the
                // pane is left alone rather than interrupted on the caller's behalf.
                case TIMED_OUT -> "still running after the deadline, so far: " + String.join(" / ", ran.output());
                case SERVER_GONE -> "the tmux server went away while the command ran";
            };
        }
    }
}
