package com.git_pull.libtmux;

import com.git_pull.libtmux.snapshot.ClientState;
import com.git_pull.libtmux.snapshot.ServerSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One attached tmux client, as one capture saw it.
 *
 * <p>Identity is the server and the client name, which is how tmux addresses it. What it is
 * attached to is state: a client can switch sessions without becoming a different client.
 */
public final class Client {

    private final Server server;
    private final ServerSnapshot snapshot;
    private final ClientState state;

    Client(Server server, ServerSnapshot snapshot, ClientState state) {
        this.server = server;
        this.snapshot = snapshot;
        this.state = state;
    }

    /** The client's terminal name, which is how tmux addresses it. */
    public String name() {
        return state.name();
    }

    /**
     * Detaches this client, leaving whatever it was attached to running.
     *
     * <p>Detaching is not killing: the session outlives the client, which is the reason tmux exists.
     */
    public void detach() {
        server.run(List.of("detach-client", "-t", state.name()));
    }

    /** Detaches every other client, leaving this one attached. */
    public void detachOthers() {
        server.run(List.of("detach-client", "-a", "-t", state.name()));
    }

    /**
     * Moves this client to another session.
     *
     * <p>The client keeps its identity: switching is a change of what it is looking at, not a
     * detach and a fresh attach.
     */
    public void switchTo(Session session) {
        Objects.requireNonNull(session, "session");
        server.run(
                List.of("switch-client", "-c", state.name(), "-t", session.id().value()));
    }

    /**
     * Asks tmux to redraw this client.
     *
     * <p>Not {@link #refresh()}, which takes a new capture of what tmux knows. This one is tmux's
     * {@code refresh-client}, and it changes the terminal rather than this handle.
     */
    public void redraw() {
        server.run(List.of("refresh-client", "-t", state.name()));
    }

    /** The server this client is connected to. */
    public Server server() {
        return server;
    }

    /** The session this client was attached to when captured. A pure read of the capture. */
    public Optional<Session> session() {
        return state.session().flatMap(snapshot::session).map(session -> new Session(server, snapshot, session));
    }

    /**
     * What this client was looking at when captured. A pure read: it issues no command.
     *
     * <p>Empty when the capture shows the client attached to nothing, or shows a session whose
     * active window or pane the capture did not include.
     */
    public Optional<ClientAttachment> attachment() {
        return session()
                .flatMap(session -> session.activeWindow()
                        .flatMap(window ->
                                window.activePane().map(pane -> new ClientAttachment(session, window, pane))));
    }

    /**
     * Takes a new capture and returns what this client is looking at now.
     *
     * <p>Named to say it dispatches, unlike {@link #attachment()}. Every call is one live capture.
     */
    public Optional<ClientAttachment> fetchAttachment() {
        return refresh().flatMap(Client::attachment);
    }

    /** Takes a new capture and returns this client as it is now, or empty if it has gone. */
    public Optional<Client> refresh() {
        ServerSnapshot fresh = server.snapshot();
        return fresh.clients().stream()
                .filter(client -> client.name().equals(state.name()))
                .findFirst()
                .map(client -> new Client(server, fresh, client));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Client that
                && server.identity().equals(that.server.identity())
                && state.name().equals(that.state.name());
    }

    @Override
    public int hashCode() {
        return Objects.hash(server.identity(), state.name());
    }

    @Override
    public String toString() {
        return "Client[" + state.name() + "]";
    }
}
