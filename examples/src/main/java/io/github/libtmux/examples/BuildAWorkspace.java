package io.github.libtmux.examples;

import io.github.libtmux.Layout;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.exception.ServerUnavailableException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Lays out a session the way you would set one up by hand before starting
 * work.
 *
 * <pre>{@code
 * java BuildAWorkspace.java /tmp/libtmux-java-dev/demo/s
 * }</pre>
 */
public final class BuildAWorkspace {

    private static final String DEMO = "/tmp/libtmux-java-dev/demo/s";

    private BuildAWorkspace() {}

    public static void main(String[] args) {
        Path socket = Path.of(args.length > 0 ? args[0] : DEMO);
        System.out.println(run(socket));
    }

    /**
     * Separated from {@code main} so the suite can run exactly what a reader
     * runs.
     */
    public static String run(Path socket) {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .configFile(Path.of("/dev/null"))
                .build();

        // Closing a server closes this client. The tmux server, and the
        // session, outlive the program: which is the whole point of tmux and
        // the reason nothing here kills it.
        try (Server server = Server.open(config)) {
            return layOut(server);
        }
    }

    private static String layOut(Server server) {
        Session session = sessionNamed(server, "work");

        Window editor = session.newWindow(w -> w.named("editor").detached());
        Pane shell = editor.split(split -> split.toRight());
        shell.sendLine("git status --short");

        editor.selectLayout(Layout.MAIN_VERTICAL);

        int windows = session.refresh().windows().size();
        return "session " + session.name() + " has " + windows + " windows";
    }

    // One read decides and answers. An absent daemon means the name cannot be
    // taken, so that specific failure is the other way this resolves to "not
    // found".
    private static Session sessionNamed(Server server, String name) {
        Optional<Session> existing;
        try {
            existing = server.session(name);
        } catch (ServerUnavailableException absent) {
            existing = Optional.empty();
        }
        return existing.orElseGet(() -> server.newSession(name));
    }
}
