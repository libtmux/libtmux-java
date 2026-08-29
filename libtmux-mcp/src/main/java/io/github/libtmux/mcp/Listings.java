package io.github.libtmux.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Dimensions;
import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.SessionId;
import io.github.libtmux.Window;
import io.github.libtmux.jackson.FilterJson;
import io.github.libtmux.jackson.LibTmuxModels;
import io.github.libtmux.query.FilterExpr;
import io.github.libtmux.snapshot.ServerSnapshot;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
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

    record Windows(
            int count,
            List<WindowSummary> windows,
            @Nullable String note) {}

    record Sessions(
            int count,
            List<SessionSummary> sessions,
            @Nullable String note) {}

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
            @Nullable String version,
            @Nullable String callerPane,
            int sessions,
            int windows,
            int panes,
            String safety,
            String note) {}

    record KnownServer(
            String socket,
            ServerDiscovery.State state,
            @Nullable Integer sessions,
            @Nullable String note) {}

    record Servers(
            int count,
            List<KnownServer> servers,
            boolean truncated,
            @Nullable String scanNote,
            String note) {}

    static Sessions sessions(Server server) {
        return sessions(server, ignored -> false);
    }

    /** Lists sessions without treating this connection's own control clients as people. */
    static Sessions sessions(Connection connection) {
        return connection.withStableClients(() -> sessions(connection.server(), connection::isOurs));
    }

    static SessionSummary session(Connection connection, String name) {
        return sessions(connection).sessions().stream()
                .filter(session -> session.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no session named " + name));
    }

    private static Sessions sessions(Server server, Predicate<String> hiddenClient) {
        ServerSnapshot snapshot = server.snapshot();
        Set<SessionId> attached = new LinkedHashSet<>();
        snapshot.clients().stream()
                .filter(client -> !hiddenClient.test(client.name()))
                .flatMap(client -> client.session().stream())
                .forEach(attached::add);
        List<SessionSummary> summaries = snapshot.sessions().stream()
                .map(session -> new SessionSummary(
                        session.id().value(),
                        session.name(),
                        attached.contains(session.id()),
                        snapshot.windowsOf(session.id()).size(),
                        snapshot.windowsOf(session.id()).stream()
                                .map(window -> window.name())
                                .toList()))
                .toList();
        return new Sessions(summaries.size(), summaries, emptiness(server, summaries.size(), "session"));
    }

    static Windows windows(Call call) {
        Server server = call.server();
        Stream<Window> windows = server.windows().stream();
        String session = call.maybe("session").orElse(null);
        if (session != null) {
            Session wanted = Targets.sessionNamed(server, session);
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
        return new Windows(summaries.size(), summaries, emptiness(server, summaries.size(), "window"));
    }

    static Panes panes(Call call) {
        Server server = call.server();
        Caller caller = call.caller();
        List<Pane> panes = server.panes();
        String note = null;
        Object filter = call.arguments().get("filter");
        if (filter != null) {
            List<Pane> narrowed = panes.stream().filter(paneFilter(filter)).toList();
            note = narrowed.isEmpty() && !panes.isEmpty()
                    ? "The filter matched none of the " + panes.size() + " panes on this server. "
                            + "Call again without 'filter' to see them all."
                    : null;
            panes = narrowed;
        }
        return new Panes(
                panes.size(), describe(panes, caller), note != null ? note : emptiness(server, panes.size(), "pane"));
    }

    /**
     * Reads a filter document, and says what one looks like when it will not read.
     *
     * <p>What the parser knows is that a key was missing or a field unrecognised. What a caller
     * needs is the shape to send and the names it may use — neither of which the parser has any
     * business knowing, and both of which are free here.
     */
    private static FilterExpr<Pane> paneFilter(Object filter) {
        try {
            return FilterJson.read(JSON.valueToTree(filter), LibTmuxModels.pane());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("that filter is not a " + FilterJson.SCHEMA + " document: "
                    + e.getMessage() + ". One looks like " + Catalog.EXAMPLE_FILTER
                    + " and may compare these fields only: "
                    + String.join(", ", LibTmuxModels.pane().fieldNames())
                    + ". To narrow by anything else — a window's name, a pane's path — list the panes "
                    + "and choose from what comes back.");
        }
    }

    /**
     * Why a listing is empty, when it is.
     *
     * <p>A server with nothing on it and a socket with no server behind it both list nothing, and a
     * model cannot tell them apart from a count. Only asked on the empty answer, so a listing that
     * found something costs no extra tmux command.
     */
    private static @Nullable String emptiness(Server server, int found, String what) {
        if (found > 0) {
            return null;
        }
        return server.isAlive()
                ? "This tmux server is running and has no " + what + "s on it."
                : "No tmux server is running on the socket this was pointed at, so there is nothing to "
                        + "list. Call tmux_list_servers to find the ones that are.";
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
        return call.connection().withStableClients(() -> {
            List<ClientSummary> summaries = call.server().clients().stream()
                    // A control client this server attached to watch for changes is not a person,
                    // and the whole point of this tool is answering whether a person is there.
                    .filter(client -> !call.connection().isOurs(client.name()))
                    .map(client -> new ClientSummary(
                            client.name(),
                            client.session().map(Session::name).orElse(null),
                            // The pane a person at this terminal is actually looking at, which is
                            // what "is anyone watching this" means in practice.
                            client.attachment()
                                    .map(attachment ->
                                            attachment.activePane().id().value())
                                    .orElse(null)))
                    .toList();
            return new Clients(
                    summaries.size(),
                    summaries,
                    summaries.isEmpty()
                            ? "Nothing is attached, so no person is watching these panes right now."
                            : null);
        });
    }

    /**
     * Which server this is, and which pane the conversation is coming through.
     *
     * <p>The last part is the one a model cannot work out for itself. Without it, "close the window
     * we are done with" can name the pane the model is talking through, and the tools that would
     * refuse to do that need to know which pane that is.
     */
    static Whoami whoami(Server server, Caller caller, Safety ceiling) {
        ServerSnapshot snapshot;
        try {
            snapshot = server.snapshot();
        } catch (LibTmuxException failed) {
            if (server.isAlive()) {
                throw failed;
            }
            return absent(server, ceiling);
        }
        if (snapshot.serverPid().isEmpty()) {
            return absent(server, ceiling);
        }
        return new Whoami(
                server.identity().realm(),
                server.identity().server(),
                socketOf(server),
                snapshot.serverVersion().orElseThrow().toString(),
                caller.pane().map(id -> id.value()).orElse(null),
                snapshot.sessions().size(),
                snapshot.windows().size(),
                snapshot.panes().size(),
                ceiling.wireName(),
                caller.pane()
                        .map(id -> "This MCP server runs in pane " + id.value()
                                + ", so acting on that pane acts on this conversation. The tools that would "
                                + "destroy it refuse unless 'confirm_self' is set.")
                        .orElse("This MCP server is not running inside a pane on this tmux server, "
                                + "so no pane here is special."));
    }

    private static Whoami absent(Server server, Safety ceiling) {
        return new Whoami(
                server.identity().realm(),
                server.identity().server(),
                null,
                null,
                null,
                0,
                0,
                0,
                ceiling.wireName(),
                "No tmux server is running on the socket this was pointed at. Nothing here can act "
                        + "until one is, and tmux_new_session will start one. Call tmux_list_servers to see "
                        + "the servers that are running — the sessions you expected are probably on one of "
                        + "them, and a different socket cannot see them.");
    }

    private static @Nullable String socketOf(Server server) {
        try {
            String reported = server.expand("#{socket_path}");
            return reported.isEmpty() ? null : reported;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Shapes a bounded typed socket inventory for the protocol. */
    static Servers servers(Server server) {
        Path currentSocket = currentSocket(server);
        ServerDiscovery.Result discovery =
                ServerDiscovery.system().discover(server.config().binary(), currentSocket);
        List<KnownServer> found = discovery.servers().stream()
                .map(known -> new KnownServer(known.socket().toString(), known.state(), known.sessions(), known.note()))
                .toList();
        return new Servers(
                found.size(),
                found,
                discovery.truncated(),
                discovery.scanNote(),
                "Point another server at one of these with the --socket flag, or set LIBTMUX_SOCKET. "
                        + (currentSocket == null
                                ? "This connection did not name an exact socket path."
                                : "This connection names " + currentSocket + "."));
    }

    private static @Nullable Path currentSocket(Server server) {
        String live = socketOf(server);
        if (live != null && !live.isBlank()) {
            try {
                return Path.of(live);
            } catch (RuntimeException ignored) {
                // Fall back to an explicitly configured path when tmux reported unusable text.
            }
        }
        return server.config().endpoint() instanceof ServerEndpoint.SocketPath socketPath ? socketPath.path() : null;
    }
}
