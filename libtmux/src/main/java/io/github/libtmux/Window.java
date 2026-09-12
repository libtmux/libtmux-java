package io.github.libtmux;

import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.snapshot.WindowContext;
import io.github.libtmux.snapshot.WindowState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One tmux window at one of its positions, as one capture saw it.
 *
 * <p>Identity is the winlink: the server, the session, the index and the window. A window linked
 * into two sessions is one window and two handles, because tmux orders and addresses those
 * positions separately. {@link #id()} stays available for asking whether two links are the same
 * underlying window.
 */
public final class Window {

    private final Server server;
    private final ServerSnapshot snapshot;
    private final WindowState state;

    Window(Server server, ServerSnapshot snapshot, WindowState state) {
        this.server = server;
        this.snapshot = snapshot;
        this.state = state;
    }

    /** The underlying window, shared by every link to it. */
    public WindowId id() {
        return state.context().window();
    }

    /** Where this link sits in its session. */
    public WindowIndex index() {
        return state.context().index();
    }

    /** The winlink this handle addresses. */
    public WindowContext context() {
        return state.context();
    }

    /** The window name. */
    public String name() {
        return state.name();
    }

    /** Whether this was its session's active window when captured. */
    public boolean active() {
        return state.active();
    }

    /** Whether the underlying window is linked into more than one session. */
    public boolean linked() {
        return state.linked();
    }

    /** How large the window was when captured, in terminal cells. */
    public Dimensions size() {
        return state.size();
    }

    /** tmux's own serialized layout, which can be handed straight back to select-layout. */
    public String layout() {
        return state.layout();
    }

    /**
     * The pane tmux had active here. A pure read of the capture.
     *
     * <p>Empty only when the capture holds no pane marked active for this window, which a complete
     * capture of a live window does not.
     */
    public Optional<Pane> activePane() {
        return panes().stream().filter(Pane::active).findFirst();
    }

    /** Makes this the active window of its session. */
    public void select() {
        server.run(snapshot, state.context(), List.of("select-window", "-t", linkTarget()));
    }

    /** The server this window lives on. */
    public Server server() {
        return server;
    }

    ServerSnapshot snapshot() {
        return snapshot;
    }

    /** The session this link belongs to. A pure read of the capture. */
    public Session session() {
        return snapshot.session(state.context().session())
                .map(session -> new Session(server, snapshot, session))
                .orElseThrow(() -> new LibTmuxException("the capture holds a window whose session it never saw"));
    }

    /** This window's own hooks, which every link to it shares. */
    public Hooks hooks() {
        return Hooks.window(server, snapshot, id());
    }

    /** This window's own options, which every link to it shares. */
    public Options options() {
        return Options.window(server, snapshot, id());
    }

    /** This link's panes, in tmux's order. A pure read of the capture. */
    public List<Pane> panes() {
        return snapshot.panesOf(state.context()).stream()
                .map(pane -> new Pane(server, snapshot, pane))
                .toList();
    }

    /**
     * Splits this window's active pane in half, putting the new one below it.
     *
     * @return the pane that appeared
     */
    public Pane split() {
        return split(SplitSpec.builder().build());
    }

    /**
     * Splits this window's active pane as described.
     *
     * @param configure receives a builder holding tmux's defaults
     * @return the pane that appeared
     * @throws UnsupportedTmuxVersion if the spec asks for something this server does not have
     */
    public Pane split(Consumer<SplitSpec.Builder> configure) {
        SplitSpec.Builder builder = SplitSpec.builder();
        configure.accept(builder);
        return split(builder.build());
    }

    /**
     * Splits this window's active pane according to a spec, which may be reused across windows.
     *
     * @return the pane that appeared
     * @throws UnsupportedTmuxVersion if the spec asks for something this server does not have
     */
    public Pane split(SplitSpec spec) {
        return Pane.created(server, snapshot, spec.argv(target(), Pane.createdFormat(), server.version(snapshot)));
    }

    /**
     * Expands a tmux format in this window's context, and answers with what it came to.
     *
     * <p>The same escape hatch {@link Pane#expand} gives, resolved against this window.
     *
     * @param format a tmux format, usually of the shape {@code #{name}}
     * @return the expansion, whole when it spans lines and empty when the format expanded to
     *     nothing
     */
    public String expand(String format) {
        Objects.requireNonNull(format, "format");
        List<String> reported = server.run(
                        snapshot, state.context(), List.of("display-message", "-p", "-t", linkTarget(), format))
                .stdout();
        return String.join("\n", reported);
    }

    /**
     * Renames this window and returns a handle on it as it is now.
     *
     * <p>A {@code :} or {@code .} is kept as written on every supported release but 3.7, which
     * refuses the name. A kept delimiter cannot then address the window, since a target splits on
     * both. Unlike a session name, a window name is never rewritten.
     */
    public Window rename(String name) {
        server.run(snapshot, List.of("rename-window", "-t", target(), TmuxFormats.literal(name)));
        return refresh();
    }

    /** Links this window into another session, so one window sits in both. */
    public void linkTo(Session session) {
        Objects.requireNonNull(session, "session");
        server.requireSameIncarnation(snapshot, session.server(), session.snapshot());
        server.run(
                snapshot,
                List.of("link-window", "-s", target(), "-t", session.id().value()));
    }

    /**
     * Removes this link, leaving the window wherever else it is linked.
     *
     * @throws LibTmuxException if this is the window's only link, which tmux refuses to remove
     */
    public void unlink() {
        server.run(snapshot, state.context(), List.of("unlink-window", "-t", linkTarget()));
    }

    /** Moves this window into another session. */
    public void moveTo(Session session) {
        Objects.requireNonNull(session, "session");
        server.requireSameIncarnation(snapshot, session.server(), session.snapshot());
        server.run(
                snapshot,
                state.context(),
                List.of("move-window", "-s", linkTarget(), "-t", session.id().value()));
    }

    /** Moves this window to an exact index in another session. */
    public void moveTo(Session session, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("window index is negative: " + index);
        }
        Objects.requireNonNull(session, "session");
        server.requireSameIncarnation(snapshot, session.server(), session.snapshot());
        server.run(
                snapshot,
                state.context(),
                List.of("move-window", "-s", linkTarget(), "-t", session.id().value() + ":" + index));
    }

    /** Resizes this window in terminal cells. */
    public void resizeTo(Dimensions size) {
        Objects.requireNonNull(size, "size");
        server.run(
                snapshot,
                List.of(
                        "resize-window",
                        "-t",
                        target(),
                        "-x",
                        Integer.toString(size.width()),
                        "-y",
                        Integer.toString(size.height())));
    }

    /**
     * Copies input to one pane into every pane in this window, until {@link #stopSynchronizingPanes}.
     *
     * <p>A verb pair rather than a boolean parameter, so a call site says which it means — as
     * {@link Pane#pipeTo} and {@link Pane#stopPiping} already do for the other state a window
     * carries.
     */
    public void synchronizePanes() {
        options().set("synchronize-panes", "on");
    }

    /** Stops copying input between this window's panes. */
    public void stopSynchronizingPanes() {
        options().set("synchronize-panes", "off");
    }

    /** Rotates the panes within this window. */
    public void rotate() {
        server.run(snapshot, List.of("rotate-window", "-t", target()));
    }

    /**
     * Rearranges this window's panes into one of tmux's built-in layouts.
     *
     * @throws UnsupportedTmuxVersion if the layout arrived after the release this server runs
     */
    public void selectLayout(Layout layout) {
        Objects.requireNonNull(layout, "layout");
        layout.requireSupported(server.version(snapshot));
        server.run(snapshot, List.of("select-layout", "-t", target(), layout.tmuxName()));
    }

    /** Moves to the next built-in layout, as tmux's own binding does. */
    public void nextLayout() {
        server.run(snapshot, List.of("next-layout", "-t", target()));
    }

    /**
     * Restores an exact arrangement previously read from {@link #layout()}.
     *
     * <p>The string is checked here rather than by tmux, because tmux 3.3a does not survive being
     * handed one it cannot parse: it ends the server and every session on the socket. Every other
     * supported release answers {@code invalid layout}. Since a layout string carries tmux's own
     * checksum, a wrong one is detectable without asking.
     *
     * @throws IllegalArgumentException if the string is not a layout tmux wrote
     */
    public void applyLayout(String layout) {
        Objects.requireNonNull(layout, "layout");
        server.run(snapshot, List.of("select-layout", "-t", target(), Layouts.requireSerialized(layout)));
    }

    /** Kills what is running in this window and starts it again. */
    public void respawn() {
        server.run(snapshot, List.of("respawn-window", "-k", "-t", target()));
    }

    /**
     * Shows a popup over this window, running a command in it.
     *
     * <p>tmux draws a popup for a client, so this needs one attached; on a detached session tmux
     * reports that it has no current client.
     *
     * <p>tmux expands {@code #(...)} in this command before a shell sees it, and shell quoting does
     * not prevent that. Pass any interpolated value through {@link TmuxFormats#literal} unless you
     * mean it to be expanded.
     */
    public void displayPopup(String shellCommand) {
        server.run(snapshot, List.of("display-popup", "-E", "-t", target(), shellCommand));
    }

    /** Closes this window. */
    public void kill() {
        server.run(snapshot, List.of("kill-window", "-t", target()));
    }

    /**
     * Takes a new capture and returns this winlink as it is now.
     *
     * @throws ObjectDoesNotExist if this window is no longer linked here
     */
    public Window refresh() {
        ServerSnapshot fresh = server.refresh(snapshot);
        return fresh.window(state.context())
                .map(window -> new Window(server, fresh, window))
                .orElseThrow(() -> new ObjectDoesNotExist("window " + id() + " no longer exists here"));
    }

    /** Addresses the underlying window, which every link to it shares. */
    private String target() {
        return state.context().window().value();
    }

    /** Addresses this exact link, even when its underlying window appears twice in one session. */
    private String linkTarget() {
        return state.context().session().value() + ":" + state.context().index().value();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Window that
                && server.identity(snapshot).equals(that.server.identity(that.snapshot))
                && state.context().equals(that.state.context());
    }

    @Override
    public int hashCode() {
        return Objects.hash(server.identity(snapshot), state.context());
    }

    @Override
    public String toString() {
        return "Window[" + state.context().session() + ":" + state.context().index() + " " + state.name() + "]";
    }
}
