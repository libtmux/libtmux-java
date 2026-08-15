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
        server.run(List.of("select-window", "-t", target()));
    }

    /** The server this window lives on. */
    public Server server() {
        return server;
    }

    /** The session this link belongs to. A pure read of the capture. */
    public Session session() {
        return snapshot.session(state.context().session())
                .map(session -> new Session(server, snapshot, session))
                .orElseThrow(() -> new LibTmuxException("the capture holds a window whose session it never saw"));
    }

    /** This window's own hooks, which every link to it shares. */
    public Hooks hooks() {
        return Hooks.window(server, id());
    }

    /** This window's own options, which every link to it shares. */
    public Options options() {
        return Options.window(server, id());
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
        return Pane.created(server, spec.argv(target(), Pane.createdFormat(), server.version()));
    }

    /**
     * Expands a tmux format in this window's context, and answers with what it came to.
     *
     * <p>The same escape hatch {@link Pane#expand} gives, resolved against this window.
     *
     * @param format a tmux format, usually of the shape {@code #{name}}
     * @return the expansion, empty when the format expanded to nothing
     */
    public String expand(String format) {
        Objects.requireNonNull(format, "format");
        List<String> reported = server.run(List.of("display-message", "-p", "-t", target(), format))
                .stdout();
        return reported.isEmpty() ? "" : reported.get(0);
    }

    /** Renames this window and returns a handle on it as it is now. */
    public Window rename(String name) {
        server.run(List.of("rename-window", "-t", target(), name));
        return refresh();
    }

    /** Links this window into another session, so one window sits in both. */
    public void linkTo(Session session) {
        server.run(List.of("link-window", "-s", target(), "-t", session.id().value()));
    }

    /**
     * Removes this link, leaving the window wherever else it is linked.
     *
     * @throws LibTmuxException if this is the window's only link, which tmux refuses to remove
     */
    public void unlink() {
        server.run(List.of("unlink-window", "-t", target()));
    }

    /** Moves this window into another session. */
    public void moveTo(Session session) {
        server.run(List.of("move-window", "-s", target(), "-t", session.id().value()));
    }

    /** Rotates the panes within this window. */
    public void rotate() {
        server.run(List.of("rotate-window", "-t", target()));
    }

    /**
     * Rearranges this window's panes into one of tmux's built-in layouts.
     *
     * @throws UnsupportedTmuxVersion if the layout arrived after the release this server runs
     */
    public void selectLayout(Layout layout) {
        Objects.requireNonNull(layout, "layout");
        TmuxVersion running = server.version();
        if (!running.atLeast(layout.since())) {
            throw new UnsupportedTmuxVersion("the " + layout + " layout", layout.since(), running);
        }
        server.run(List.of("select-layout", "-t", target(), layout.tmuxName()));
    }

    /** Moves to the next built-in layout, as tmux's own binding does. */
    public void nextLayout() {
        server.run(List.of("next-layout", "-t", target()));
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
        if (!isTmuxLayout(layout)) {
            throw new IllegalArgumentException("not a layout tmux wrote: " + layout);
        }
        server.run(List.of("select-layout", "-t", target(), layout));
    }

    /**
     * Whether a string carries the checksum tmux puts on a layout it wrote.
     *
     * <p>tmux prefixes the arrangement with four hex digits and a comma, summing the rest with a
     * rotate-and-add over 16 bits. Recomputing it is the whole check: a string that passes is one
     * tmux produced, and 3.3a is safe to hand it to.
     */
    private static boolean isTmuxLayout(String layout) {
        if (layout.length() < 6 || layout.charAt(4) != ',') {
            return false;
        }
        int declared;
        try {
            declared = Integer.parseInt(layout.substring(0, 4), 16);
        } catch (NumberFormatException notHex) {
            return false;
        }
        int checksum = 0;
        for (int i = 5; i < layout.length(); i++) {
            checksum = ((checksum >> 1) + ((checksum & 1) << 15)) & 0xffff;
            checksum = (checksum + layout.charAt(i)) & 0xffff;
        }
        return checksum == declared;
    }

    /** Kills what is running in this window and starts it again. */
    public void respawn() {
        server.run(List.of("respawn-window", "-k", "-t", target()));
    }

    /**
     * Shows a popup over this window, running a command in it.
     *
     * <p>tmux draws a popup for a client, so this needs one attached; on a detached session tmux
     * reports that it has no current client.
     */
    public void displayPopup(String shellCommand) {
        server.run(List.of("display-popup", "-E", "-t", target(), shellCommand));
    }

    /** Closes this window. */
    public void kill() {
        server.run(List.of("kill-window", "-t", target()));
    }

    /**
     * Takes a new capture and returns this winlink as it is now.
     *
     * @throws ObjectDoesNotExist if this window is no longer linked here
     */
    public Window refresh() {
        ServerSnapshot fresh = server.snapshot();
        return fresh.window(state.context())
                .map(window -> new Window(server, fresh, window))
                .orElseThrow(() -> new ObjectDoesNotExist("window " + id() + " no longer exists here"));
    }

    /** Addresses the underlying window, which every link to it shares. */
    private String target() {
        return state.context().window().value();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Window that
                && server.identity().equals(that.server.identity())
                && state.context().equals(that.state.context());
    }

    @Override
    public int hashCode() {
        return Objects.hash(server.identity(), state.context());
    }

    @Override
    public String toString() {
        return "Window[" + state.context().session() + ":" + state.context().index() + " " + state.name() + "]";
    }
}
