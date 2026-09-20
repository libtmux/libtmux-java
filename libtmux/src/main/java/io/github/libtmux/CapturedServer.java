package io.github.libtmux;

import io.github.libtmux.snapshot.ServerSnapshot;
import java.util.List;

/**
 * Retains metadata and handles from one authenticated server capture.
 *
 * <p>Lists are immutable and preserve tmux order, including window links and pane occurrences.
 * Accessors and captured handle relations issue no commands and remain readable after the server
 * closes. Explicit handle operations still use the owning server and its PID fence.
 *
 * <p>The capture is an observed consistent graph, not a tmux transaction. Its identity carries
 * the observed daemon PID, not a generation that distinguishes PID reuse.
 */
public final class CapturedServer {

    private final Server server;
    private final ServerSnapshot snapshot;

    CapturedServer(Server server, ServerSnapshot snapshot) {
        this.server = server;
        this.snapshot = snapshot;
    }

    /** Returns the server that acquired this capture. */
    public Server server() {
        return server;
    }

    /** Returns the identity bound to the captured daemon PID without probing tmux. */
    public ServerIdentity identity() {
        return server.identity(snapshot);
    }

    /** Returns the immutable snapshot that supplies every handle in this view. */
    public ServerSnapshot snapshot() {
        return snapshot;
    }

    /** Returns captured sessions in tmux order. */
    public List<Session> sessions() {
        return snapshot.sessions().stream()
                .map(state -> new Session(server, snapshot, state))
                .toList();
    }

    /** Returns captured window links in tmux order, retaining each placement. */
    public List<Window> windows() {
        return snapshot.windows().stream()
                .map(state -> new Window(server, snapshot, state))
                .toList();
    }

    /** Returns captured pane occurrences in tmux order, retaining each window placement. */
    public List<Pane> panes() {
        return snapshot.panes().stream()
                .map(state -> new Pane(server, snapshot, state))
                .toList();
    }

    /** Returns captured clients in tmux order. */
    public List<Client> clients() {
        return snapshot.clients().stream()
                .map(state -> new Client(server, snapshot, state))
                .toList();
    }
}
