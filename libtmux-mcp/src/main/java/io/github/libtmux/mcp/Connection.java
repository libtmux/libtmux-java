package io.github.libtmux.mcp;

import io.github.libtmux.Server;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every tool, resource and prompt on this connection shares.
 *
 * <p>The caller's own pane is worked out once, when the connection is made, rather than on every
 * call: finding it costs a tmux command, it cannot change while this process runs, and a guard that
 * charges for itself on every call is one somebody will be tempted to remove.
 *
 * @param server the tmux server this connection acts on
 * @param caller the pane this process runs in, when it runs in one on that server
 * @param ceiling the most damage this server is configured to allow
 */
record Connection(Server server, Caller caller, Safety ceiling, Set<String> ownClients) {

    static Connection to(Server server, Safety ceiling) {
        return new Connection(server, Caller.of(server), ceiling, ConcurrentHashMap.newKeySet());
    }

    /**
     * Stops a client this server attached for its own purposes being reported as a person.
     *
     * <p>Watching a server means attaching a control client to it, and an attached client is exactly
     * what {@code tmux_list_clients} answers "is anybody looking at this" with. Left in, this
     * server's own watcher would make every session look occupied.
     */
    void hide(String clientName) {
        ownClients.add(clientName);
    }

    boolean isOurs(String clientName) {
        return ownClients.contains(clientName);
    }

    /** One invocation on this connection. */
    Call call(java.util.Map<String, Object> arguments, Call.Progress progress) {
        return new Call(this, arguments, progress);
    }
}
