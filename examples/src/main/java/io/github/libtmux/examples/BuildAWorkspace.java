package io.github.libtmux.examples;

import io.github.libtmux.Layout;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Lays out a session the way you would set one up by hand before starting work.
 *
 * <pre>{@code
 * java BuildAWorkspace.java /tmp/libtmux-java-dev/demo/s
 * }</pre>
 */
public final class BuildAWorkspace {

    private static final String ARENA_ARTIFACT = "java-build-a-workspace";

    private BuildAWorkspace() {}

    public static void main(String[] args) {
        Optional<ServerConfig> arena = arenaConfig(System.getenv());
        if (arena.isPresent()) {
            System.out.println("LIBTMUX_ARENA_EVIDENCE="
                    + ArenaSupport.run(ARENA_ARTIFACT, arena.orElseThrow(), BuildAWorkspace::run));
            return;
        }
        run(Path.of(args.length > 0 ? args[0] : "/tmp/libtmux-java-dev/demo/s"));
    }

    static Optional<ServerConfig> arenaConfig(Map<String, String> environment) {
        return ArenaSupport.config(environment, ARENA_ARTIFACT);
    }

    /** Separated from {@code main} so the suite can run exactly what a reader runs. */
    public static String run(Path socket) {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        // Closing a server closes this client. The tmux server, and the session, outlive the program
        // — which is the whole point of tmux and the reason nothing here kills it.
        try (Server server = Server.open(config)) {
            return run(server);
        }
    }

    static String run(Server server) {
        Session session = ArenaSupport.ownSession(server, "work");

        Window editor = session.newWindow(window -> window.named("editor").detached());
        Pane shell = editor.split(split -> split.toRight());
        shell.sendLine("git status --short");

        editor.selectLayout(Layout.MAIN_VERTICAL);

        return "session " + session.name() + " has "
                + session.refresh().windows().size() + " windows";
    }
}
