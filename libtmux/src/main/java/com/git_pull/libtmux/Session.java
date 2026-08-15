package com.git_pull.libtmux;

import com.git_pull.libtmux.format.RowFormat;
import com.git_pull.libtmux.snapshot.ServerSnapshot;
import com.git_pull.libtmux.snapshot.SessionState;
import com.git_pull.libtmux.snapshot.WindowContext;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One tmux session, as one capture saw it.
 *
 * <p>Identity is the server and the session id. A name is state: a user can rename a session at any
 * time, and a handle whose equality included the name would stop matching itself the moment they
 * did.
 *
 * <p>{@link #windows()} reads the capture this handle came from and issues no command. To see newer
 * state, take a new capture.
 */
public final class Session {

    private static final RowFormat CREATED = RowFormat.of("session_id", "window_id", "window_index");

    private final Server server;
    private final ServerSnapshot snapshot;
    private final SessionState state;

    Session(Server server, ServerSnapshot snapshot, SessionState state) {
        this.server = server;
        this.snapshot = snapshot;
        this.state = state;
    }

    /** The session's stable id. */
    public SessionId id() {
        return state.id();
    }

    /** The session name, which a user may change at any time. */
    public String name() {
        return state.name();
    }

    /** Whether a client was attached when this was captured. */
    public boolean attached() {
        return state.attached();
    }

    /** The server this session lives on. */
    public Server server() {
        return server;
    }

    /** The window tmux had active in this session. A pure read of the capture. */
    public java.util.Optional<Window> activeWindow() {
        return windows().stream().filter(Window::active).findFirst();
    }

    /** The active pane of the active window. A pure read of the capture. */
    public java.util.Optional<Pane> activePane() {
        return activeWindow().flatMap(Window::activePane);
    }

    /** Makes a window of this session the active one. */
    public void selectWindow(Window window) {
        server.run(List.of("select-window", "-t", window.id().value()));
    }

    /** Moves to the next window in this session, wrapping at the end. */
    public void nextWindow() {
        server.run(List.of("next-window", "-t", state.id().value()));
    }

    /** Moves to the previous window in this session, wrapping at the start. */
    public void previousWindow() {
        server.run(List.of("previous-window", "-t", state.id().value()));
    }

    /**
     * Returns to the window that was active before this one.
     *
     * @throws LibTmuxException if nothing else has been active yet, which tmux reports rather than
     *     silently staying put
     */
    public void lastWindow() {
        server.run(List.of("last-window", "-t", state.id().value()));
    }

    /** Detaches every client attached to this session, leaving the session running. */
    public void detachClients() {
        server.run(List.of("detach-client", "-s", state.id().value()));
    }

    /** This session's own options. */
    public Options options() {
        return Options.session(server, state.id());
    }

    /** This session's own hooks. */
    public Hooks hooks() {
        return Hooks.session(server, state.id());
    }

    /** This session's windows, in tmux's order. A pure read of the capture. */
    public List<Window> windows() {
        return snapshot.windowsOf(state.id()).stream()
                .map(window -> new Window(server, snapshot, window))
                .toList();
    }

    /**
     * Creates a window in this session and returns that exact window.
     *
     * <p>The creating command reports which window it made, rather than the name being looked up
     * afterwards: two windows may share a name, and then a lookup cannot say which one is new.
     *
     * @param name the window name
     * @return a handle on the created window, from a fresh capture
     */
    public Window newWindow(String name) {
        return newWindow(WindowSpec.builder().named(name).build());
    }

    /**
     * Creates a window in this session as described.
     *
     * <pre>{@code
     * Window logs = session.newWindow(w -> w.named("logs").running("journalctl", "-f"));
     * }</pre>
     *
     * @param configure receives a builder holding tmux's defaults
     * @return a handle on the created window, from a fresh capture
     * @throws UnsupportedTmuxVersion if the spec asks for something this server does not have
     */
    public Window newWindow(Consumer<WindowSpec.Builder> configure) {
        WindowSpec.Builder builder = WindowSpec.builder();
        configure.accept(builder);
        return newWindow(builder.build());
    }

    /**
     * Creates a window in this session according to a spec, which may be reused across sessions.
     *
     * @return a handle on the created window, from a fresh capture
     * @throws UnsupportedTmuxVersion if the spec asks for something this server does not have
     */
    public Window newWindow(WindowSpec spec) {
        List<String> reported = server.run(spec.argv(state.id().value(), CREATED.template(), server.version()))
                .stdout();
        ServerSnapshot fresh = server.snapshot();
        if (reported.isEmpty()) {
            // Only reuseExisting gets here: tmux selects the window it already had and reports
            // nothing, so the answer has to come from a lookup. See docs/spikes/14.
            return spec.name()
                    .flatMap(wanted -> new Session(server, fresh, state)
                            .windows().stream()
                                    .filter(window -> wanted.equals(window.name()))
                                    .findFirst())
                    .orElseThrow(() -> new ObjectDoesNotExist("tmux reported no window and none carries that name"));
        }
        List<String> fields = CREATED.split(reported.get(0));
        WindowContext created = new WindowContext(
                new SessionId(fields.get(0)),
                new WindowIndex(Integer.parseInt(fields.get(2))),
                new WindowId(fields.get(1)));
        return fresh.window(created)
                .map(window -> new Window(server, fresh, window))
                .orElseThrow(() -> new ObjectDoesNotExist("the window just created is already gone"));
    }

    /**
     * Expands a tmux format in this session's context, and answers with what it came to.
     *
     * <p>The same escape hatch {@link Pane#expand} gives, resolved against this session.
     *
     * @param format a tmux format, usually of the shape {@code #{name}}
     * @return the expansion, empty when the format expanded to nothing
     */
    public String expand(String format) {
        Objects.requireNonNull(format, "format");
        List<String> reported = server.run(
                        List.of("display-message", "-p", "-t", state.id().value(), format))
                .stdout();
        return reported.isEmpty() ? "" : reported.get(0);
    }

    /** Renames this session and returns a handle on it as it is now. */
    public Session rename(String name) {
        server.run(List.of("rename-session", "-t", state.id().value(), name));
        return refresh();
    }

    /** Ends this session. Every window in it goes with it. */
    public void kill() {
        server.run(List.of("kill-session", "-t", state.id().value()));
    }

    /**
     * Takes a new capture and returns this session as it is now.
     *
     * @throws ObjectDoesNotExist if the session is gone
     */
    public Session refresh() {
        ServerSnapshot fresh = server.snapshot();
        return fresh.session(state.id())
                .map(session -> new Session(server, fresh, session))
                .orElseThrow(() -> new ObjectDoesNotExist("session " + state.id() + " no longer exists"));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Session that
                && server.identity().equals(that.server.identity())
                && state.id().equals(that.state.id());
    }

    @Override
    public int hashCode() {
        return Objects.hash(server.identity(), state.id());
    }

    @Override
    public String toString() {
        return "Session[" + state.id() + " " + state.name() + "]";
    }
}
