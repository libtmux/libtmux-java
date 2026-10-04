package io.github.libtmux.examples;

import io.github.libtmux.Pane;
import io.github.libtmux.PaneRun;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Runs a command in a pane and reads what it exited with, rather than what the
 * screen shows.
 *
 * <pre>{@code
 * java RunACommand.java /tmp/libtmux-java-dev/demo/s 'make test'
 * }</pre>
 */
public final class RunACommand {

    private static final String DEMO = "/tmp/libtmux-java-dev/demo/s";

    private RunACommand() {}

    public static void main(String[] args) throws InterruptedException {
        Path socket = Path.of(args.length > 0 ? args[0] : DEMO);
        String command = args.length > 1 ? args[1] : "printf 'built\\n'";
        System.out.println(run(socket, command));
    }

    /**
     * Separated from {@code main} so the suite can run exactly what a reader
     * runs.
     *
     * <p>{@code InterruptedException} is declared rather than swallowed: the
     * wait is tmux's, and cancelling the thread that is waiting is how a caller
     * stops waiting for it.
     *
     * @param socket the tmux socket of the server to run in
     * @param command the command line to type into the first pane
     */
    static String run(Path socket, String command) throws InterruptedException {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        try (Server server = Server.open(config)) {
            Pane pane = server.panes().get(0);

            // The status is the shell's and the wait is tmux's own: nothing
            // here is inferred from what the pane happens to be showing, and
            // the output is the command's alone: not the line that was typed,
            // not the prompt after it.
            PaneRun ran = pane.run(command, Duration.ofMinutes(2));
            return report(ran);
        }
    }

    private static String report(PaneRun ran) {
        String lines = String.join(" / ", ran.output());

        return switch (ran.outcome()) {
            case FINISHED -> {
                int status = ran.exitStatus().orElseThrow();
                int count = ran.output().size();
                yield "exit %d, %d line(s): %s".formatted(status, count, lines);
            }
            // Still running at the deadline. What it printed so far is worth
            // having, and the pane is left alone rather than interrupted on
            // the caller's behalf.
            case TIMED_OUT -> "still running at the deadline, so far: " + lines;
            case SERVER_GONE -> "the tmux server went away mid-command";
        };
    }
}
