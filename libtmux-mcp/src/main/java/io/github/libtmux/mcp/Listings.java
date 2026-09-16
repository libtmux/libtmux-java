package io.github.libtmux.mcp;

import io.github.libtmux.Dimensions;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.SessionId;
import io.github.libtmux.Window;
import io.github.libtmux.snapshot.ServerSnapshot;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    static Sessions sessions(Server server) {
        ServerSnapshot snapshot = server.snapshot();
        Set<SessionId> attached = new LinkedHashSet<>();
        snapshot.clients().stream().flatMap(client -> client.session().stream()).forEach(attached::add);
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
        return new Sessions(summaries.size(), summaries, emptiness(summaries.size(), "session"));
    }

    /** Lists sessions for the MCP connection. */
    static Sessions sessions(Connection connection) {
        return sessions(connection.server());
    }

    static SessionSummary session(Connection connection, String name) {
        return sessions(connection).sessions().stream()
                .filter(session -> session.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no session named " + name));
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
        return new Windows(summaries.size(), summaries, emptiness(summaries.size(), "window"));
    }

    static Panes panes(Call call) {
        Server server = call.server();
        Caller caller = call.caller();
        List<Pane> panes = server.panes();
        return new Panes(panes.size(), describe(panes, caller), emptiness(panes.size(), "pane"));
    }

    private static @Nullable String emptiness(int found, String what) {
        return found == 0 ? "The capture contains no " + what + "s." : null;
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
}
