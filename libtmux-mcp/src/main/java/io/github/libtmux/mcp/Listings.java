package io.github.libtmux.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Dimensions;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.jackson.FilterJson;
import io.github.libtmux.jackson.LibTmuxModels;
import io.github.libtmux.query.FilterExpr;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * What is there to act on.
 *
 * <p>Every listing hands back the id other tools take as a target, because a model works from a
 * listing it read some turns ago and positions move as neighbours come and go.
 *
 * <p>Summaries are deliberately narrow. A model reading forty panes pays for every field on each of
 * them, and the fields here are the ones that decide which pane it wants: what is running, where,
 * and whether it is the one the conversation is coming through.
 */
final class Listings {

    /** Reads the filter document a model sent. Plain: this parses input rather than writing answers. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private Listings() {}

    /** @param caller true only on the pane this server is itself running in, and null otherwise */
    record PaneSummary(
            String id,
            String session,
            String window,
            String windowId,
            String command,
            String path,
            String size,
            boolean active,
            @Nullable Boolean caller) {}

    record WindowSummary(String id, int index, String name, String session, boolean active, int panes) {}

    record SessionSummary(String id, String name, boolean attached, int windows, List<String> windowNames) {}

    record ClientSummary(
            String name, @Nullable String session, @Nullable String watching) {}

    record Panes(
            int count, List<PaneSummary> panes, @Nullable String note) {}

    record Windows(int count, List<WindowSummary> windows) {}

    record Sessions(int count, List<SessionSummary> sessions) {}

    record Clients(
            int count,
            List<ClientSummary> clients,
            @Nullable String note) {}

    /**
     * @param socket where this server listens, which is what another tool would be pointed at
     * @param callerPane the pane this MCP server runs in, absent when it does not run in one here
     */
    record Whoami(
            String realm,
            String server,
            @Nullable String socket,
            String version,
            @Nullable String callerPane,
            int sessions,
            int windows,
            int panes,
            String safety,
            String note) {}

    record KnownServer(
            String socket,
            boolean alive,
            @Nullable Integer sessions,
            @Nullable String note) {}

    record Servers(int count, List<KnownServer> servers, String note) {}

    static Sessions sessions(Server server) {
        List<SessionSummary> summaries = server.sessions().stream()
                .map(session -> new SessionSummary(
                        session.id().value(),
                        session.name(),
                        session.attached(),
                        session.windows().size(),
                        session.windows().stream().map(Window::name).toList()))
                .toList();
        return new Sessions(summaries.size(), summaries);
    }

    static Windows windows(Call call) {
        Server server = call.server();
        Stream<Window> windows = server.windows().stream();
        String session = call.maybe("session").orElse(null);
        if (session != null) {
            Session wanted = Targets.session(server, session);
            windows = wanted.windows().stream();
        }
        List<WindowSummary> summaries = windows.map(window -> new WindowSummary(
                        window.id().value(),
                        window.index().value(),
                        window.name(),
                        window.session().name(),
                        window.active(),
                        window.panes().size()))
                .toList();
        return new Windows(summaries.size(), summaries);
    }

    static Panes panes(Call call) {
        Server server = call.server();
        Caller caller = call.caller();
        List<Pane> panes = server.panes();
        String note = null;
        Object filter = call.arguments().get("filter");
        if (filter != null) {
            FilterExpr<Pane> expression = FilterJson.read(JSON.valueToTree(filter), LibTmuxModels.pane());
            List<Pane> narrowed = panes.stream().filter(expression).toList();
            note = narrowed.isEmpty() && !panes.isEmpty()
                    ? "The filter matched none of the " + panes.size() + " panes on this server. "
                            + "Call again without 'filter' to see them all."
                    : null;
            panes = narrowed;
        }
        return new Panes(panes.size(), describe(panes, caller), note);
    }

    static List<PaneSummary> describe(List<Pane> panes, Caller caller) {
        return panes.stream()
                .map(pane -> {
                    Dimensions size = pane.size();
                    return new PaneSummary(
                            pane.id().value(),
                            pane.window().session().name(),
                            pane.window().name(),
                            pane.window().id().value(),
                            pane.currentCommand(),
                            pane.currentPath().toString(),
                            size.width() + "x" + size.height(),
                            pane.active(),
                            caller.isSelf(pane.id()) ? Boolean.TRUE : null);
                })
                .toList();
    }

    static Clients clients(Call call) {
        Server server = call.server();
        List<ClientSummary> summaries = server.clients().stream()
                // A control client this server attached to watch for changes is not a person, and
                // the whole point of this tool is answering whether a person is there.
                .filter(client -> !call.connection().isOurs(client.name()))
                .map(client -> new ClientSummary(
                        client.name(),
                        client.session().map(Session::name).orElse(null),
                        // The pane a person at this terminal is actually looking at, which is what
                        // "is anyone watching this" means in practice.
                        client.attachment()
                                .map(attachment -> attachment.activePane().id().value())
                                .orElse(null)))
                .toList();
        return new Clients(
                summaries.size(),
                summaries,
                summaries.isEmpty() ? "Nothing is attached, so no person is watching these panes right now." : null);
    }

    /**
     * Which server this is, and which pane the conversation is coming through.
     *
     * <p>The last part is the one a model cannot work out for itself. Without it, "close the window
     * we are done with" can name the pane the model is talking through, and the tools that would
     * refuse to do that need to know which pane that is.
     */
    static Whoami whoami(Server server, Caller caller, Safety ceiling) {
        return new Whoami(
                server.identity().realm(),
                server.identity().server(),
                socketOf(server),
                server.version().toString(),
                caller.pane().map(id -> id.value()).orElse(null),
                server.sessions().size(),
                server.windows().size(),
                server.panes().size(),
                ceiling.wireName(),
                caller.pane()
                        .map(id -> "This MCP server runs in pane " + id.value()
                                + ", so acting on that pane acts on this conversation. The tools that would "
                                + "destroy it refuse unless 'confirm_self' is set.")
                        .orElse("This MCP server is not running inside a pane on this tmux server, "
                                + "so no pane here is special."));
    }

    private static @Nullable String socketOf(Server server) {
        try {
            List<String> reported =
                    server.cmd("display-message", "-p", "#{socket_path}").stdout();
            return reported.isEmpty() ? null : reported.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Every tmux server this user has, so a model pointed at the wrong one can find the right one.
     *
     * <p>tmux keeps its sockets in one directory per user, so the list is what is in that directory
     * rather than anything this server was told. A socket file outlives the server that made it, so
     * each is asked whether it answers rather than assumed to.
     */
    static Servers servers(Server server, String binary) {
        Path directory = socketDirectory();
        List<KnownServer> found;
        try (Stream<Path> entries = Files.list(directory)) {
            found = entries.filter(Listings::isSocket)
                    .sorted(Comparator.comparing(Path::toString))
                    // A directory of sockets is small, and each probe is a process; a bound keeps a
                    // pathological directory from turning one call into hundreds of them.
                    .limit(32)
                    .map(socket -> probe(socket, binary))
                    .toList();
        } catch (IOException e) {
            found = List.of();
        }
        return new Servers(
                found.size(),
                found,
                "Point another server at one of these with the --socket flag, or set LIBTMUX_SOCKET. "
                        + "This one is on " + socketOf(server) + ".");
    }

    /** A unix socket is neither a file nor a directory, which is all "other" means here. */
    private static boolean isSocket(Path path) {
        try {
            return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class)
                    .isOther();
        } catch (IOException e) {
            return false;
        }
    }

    private static KnownServer probe(Path socket, String binary) {
        Server candidate = null;
        try {
            candidate = Server.open(ServerConfig.builder()
                    .binary(binary)
                    .endpoint(ServerEndpoint.socketPath(socket))
                    .build());
            List<Session> sessions = candidate.sessions();
            return new KnownServer(socket.toString(), true, sessions.size(), null);
        } catch (RuntimeException e) {
            return new KnownServer(socket.toString(), false, null, "not answering; the socket file is left over");
        } finally {
            if (candidate != null) {
                candidate.close();
            }
        }
    }

    /** tmux puts a user's sockets under {@code TMUX_TMPDIR}, falling back to {@code /tmp}. */
    private static Path socketDirectory() {
        String configured = System.getenv("TMUX_TMPDIR");
        Path root = Path.of(configured == null || configured.isEmpty() ? "/tmp" : configured);
        return root.resolve("tmux-" + uid());
    }

    private static String uid() {
        try {
            Process process = new ProcessBuilder("id", "-u").start();
            try (var reader = process.inputReader()) {
                String line = reader.readLine();
                return line == null ? "0" : line.trim();
            }
        } catch (IOException e) {
            return "0";
        }
    }
}
