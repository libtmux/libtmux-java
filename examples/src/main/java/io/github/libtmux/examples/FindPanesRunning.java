package io.github.libtmux.examples;

import io.github.libtmux.Pane;
import io.github.libtmux.Pane_;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds the panes running a given command, without asking tmux more than once.
 *
 * <pre>{@code
 * java FindPanesRunning.java /tmp/libtmux-java-dev/demo/s vim
 * }</pre>
 */
public final class FindPanesRunning {

    private FindPanesRunning() {}

    public static void main(String[] args) {
        Path socket = Path.of(args.length > 0 ? args[0] : "/tmp/libtmux-java-dev/demo/s");
        String command = args.length > 1 ? args[1] : "vim";
        run(socket, command).forEach(pane -> System.out.println(pane.id().value() + "  " + pane.currentCommand()));
    }

    /** Separated from {@code main} so the suite can run exactly what a reader runs. */
    public static List<Pane> run(Path socket, String command) {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        try (Server server = Server.open(config)) {
            // server.panes() reads tmux once. The filter runs over what that read returned, so
            // narrowing costs nothing and cannot see a half-changed server.
            return server.panes().stream()
                    .filter(Pane_.command().startsWith(command))
                    .toList();
        }
    }
}
