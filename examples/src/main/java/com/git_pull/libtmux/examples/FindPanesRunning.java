package com.git_pull.libtmux.examples;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Pane_;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.ServerConfig;
import com.git_pull.libtmux.ServerEndpoint;
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
