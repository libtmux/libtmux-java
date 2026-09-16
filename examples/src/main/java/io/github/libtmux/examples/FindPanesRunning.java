package io.github.libtmux.examples;

import io.github.libtmux.Pane;
import io.github.libtmux.Pane_;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the panes running a given command, without asking tmux more than once.
 *
 * <pre>{@code
 * java FindPanesRunning.java /tmp/libtmux-java-dev/demo/s vim
 * }</pre>
 */
public final class FindPanesRunning {

    private static final String ARENA_ARTIFACT = "java-find-panes-running";
    private static final String DEFAULT_COMMAND = "vim";

    private FindPanesRunning() {}

    public static void main(String[] args) {
        Optional<ServerConfig> arena = arenaConfig(System.getenv());
        if (arena.isPresent()) {
            System.out.println("LIBTMUX_ARENA_EVIDENCE="
                    + ArenaSupport.run(ARENA_ARTIFACT, arena.orElseThrow(), FindPanesRunning::run));
            return;
        }
        Path socket = Path.of(args.length > 0 ? args[0] : "/tmp/libtmux-java-dev/demo/s");
        String command = args.length > 1 ? args[1] : DEFAULT_COMMAND;
        run(socket, command).forEach(pane -> System.out.println(pane.id().value() + "  " + pane.currentCommand()));
    }

    static Optional<ServerConfig> arenaConfig(Map<String, String> environment) {
        return ArenaSupport.config(environment, ARENA_ARTIFACT);
    }

    /** Separated from {@code main} so the suite can run exactly what a reader runs. */
    public static List<Pane> run(Path socket, String command) {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        try (Server server = Server.open(config)) {
            return run(server, command);
        }
    }

    /** The lent-server entry point the arena runner drives, against the default command. */
    static List<Pane> run(Server server) {
        return run(server, DEFAULT_COMMAND);
    }

    static List<Pane> run(Server server, String command) {
        // server.panes() reads tmux once. The filter runs over what that read returned, so
        // narrowing costs nothing and cannot see a half-changed server.
        return server.panes().stream()
                .filter(Pane_.command().startsWith(command))
                .toList();
    }
}
