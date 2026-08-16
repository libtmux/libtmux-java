package io.github.libtmux.mcp;

import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.ControlEvent;
import java.util.Optional;
import java.util.Set;

/**
 * Tells a client when tmux has changed, instead of waiting to be asked.
 *
 * <p>A control client stays attached and tmux pushes at it: a window appearing, a session renamed, a
 * pane producing output. Each of those becomes an MCP notification naming the resource that is now
 * out of date, so a client holding {@code tmux://panes} refreshes when there is a reason to and
 * never otherwise. Between changes nothing here runs at all — the comparison happens inside tmux,
 * on its own timer.
 *
 * <p>Off unless asked for, because it is not free: watching means attaching a client, and an
 * attached client is a real change to the server. The one attached here is hidden from
 * {@code tmux_list_clients} so it cannot be mistaken for a person.
 */
final class Watches implements AutoCloseable {

    /**
     * What is watched over every pane: how far its output has got.
     *
     * <p>Not the pane's contents — a format expanding to a whole screen would be compared, and sent,
     * every second. The cursor and history position change exactly when a pane produces output,
     * which is the thing worth being told about.
     */
    private static final String PANE_PROGRESS = "#{history_size},#{cursor_y},#{cursor_x}";

    /** Notifications that mean the shape of the server changed, whatever else they carry. */
    private static final Set<String> RESHAPED = Set.of(
            "window-add",
            "window-close",
            "window-renamed",
            "window-pane-changed",
            "unlinked-window-add",
            "unlinked-window-close",
            "unlinked-window-renamed",
            "session-changed",
            "session-renamed",
            "session-window-changed",
            "sessions-changed",
            "layout-change",
            "client-session-changed",
            "client-detached");

    /**
     * Where a change is announced.
     *
     * <p>An interface rather than the MCP server itself, so what tmux pushes and what the protocol
     * sends can be tested apart. Watching real tmux is worth testing; the SDK's notification methods
     * are not.
     */
    interface Notifier {

        /** Says a resource is no longer what a client last read. */
        void updated(String uri);

        /** Says the set of resources itself has changed. */
        void listChanged();
    }

    private final ControlClient client;

    private Watches(ControlClient client) {
        this.client = client;
    }

    /**
     * Starts watching, if there is anything to attach to.
     *
     * @return the watcher, or empty when the server has no session to attach to or tmux refused
     */
    static Optional<Watches> start(Connection connection, Notifier notifier) {
        var sessions = connection.server().sessions();
        if (sessions.isEmpty()) {
            return Optional.empty();
        }
        ControlClient client;
        try {
            client = ControlClient.attach(
                    connection.server().config(), sessions.get(0).id());
        } catch (RuntimeException e) {
            // Watching is an improvement, not a requirement. A server that cannot be watched is still
            // a server every tool works against.
            return Optional.empty();
        }
        // Asked through the client itself, so the answer is that client's own name and not a guess.
        client.send("display-message", "-p", "#{client_name}").lines().stream()
                .filter(name -> !name.isBlank())
                .forEach(connection::hide);

        client.onEvent(event -> announce(notifier, event));
        client.watch("panes", "%*", PANE_PROGRESS);
        return Optional.of(new Watches(client));
    }

    private static void announce(Notifier notifier, ControlEvent event) {
        try {
            if (event.subscription().filter("panes"::equals).isPresent()) {
                // A pane produced output, so what it is showing is no longer what a client last read.
                event.paneId().ifPresent(pane -> notifier.updated("tmux://panes/" + pane + "/content"));
                return;
            }
            if (RESHAPED.contains(event.kind())) {
                notifier.updated("tmux://sessions");
                notifier.updated("tmux://panes");
                notifier.listChanged();
            }
        } catch (RuntimeException e) {
            // These run on the control client's reader thread, which also resolves every reply. A
            // client that has stopped listening must not be able to stop it reading.
        }
    }

    /** Whether the control client this watches through is still up. */
    boolean isAlive() {
        return client.isAlive();
    }

    @Override
    public void close() {
        client.close();
    }
}
