package com.git_pull.libtmux;

import com.git_pull.libtmux.format.RowFormat;
import com.git_pull.libtmux.snapshot.PaneState;
import com.git_pull.libtmux.snapshot.ServerSnapshot;
import com.git_pull.libtmux.snapshot.WindowContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One tmux pane, as one capture saw it.
 *
 * <p>Identity is the server and the pane id. An index is state: panes renumber as neighbours come
 * and go.
 */
public final class Pane {

    private static final RowFormat BROKEN_OUT = RowFormat.of("session_id", "window_id", "window_index");

    private static final RowFormat CREATED = RowFormat.of("pane_id");

    /**
     * tmux 3.7 exactly gets break-pane's naming wrong twice: it ends the whole server — every
     * session on the socket — when it has to choose the name itself, and it silently discards a name
     * that is given. 3.7a fixed both.
     */
    private static final TmuxVersion BREAK_PANE_NAMING_BROKEN = new TmuxVersion(3, 7, "");

    private final Server server;
    private final ServerSnapshot snapshot;
    private final PaneState state;

    Pane(Server server, ServerSnapshot snapshot, PaneState state) {
        this.server = server;
        this.snapshot = snapshot;
        this.state = state;
    }

    /** The pane's stable id. */
    public PaneId id() {
        return state.id();
    }

    /** The pane's position, which shifts as neighbours come and go. */
    public int index() {
        return state.index();
    }

    /** Whether this was its window's active pane when captured. */
    public boolean active() {
        return state.active();
    }

    /** The command tmux reported running here. */
    public String currentCommand() {
        return state.currentCommand();
    }

    /**
     * Whether this pane floats, or empty when the running tmux cannot say.
     *
     * <p>Empty is not "no". tmux before 3.7 expands the format to nothing, and answering {@code false}
     * there would be indistinguishable from a tmux that looked and found the pane was not floating.
     */
    public java.util.Optional<Boolean> floating() {
        return state.floating();
    }

    /** How large the pane was when captured, in terminal cells. */
    public Dimensions size() {
        return state.size();
    }

    /** The pane title, which a program running inside it can change. */
    public String title() {
        return state.title();
    }

    /** The working directory tmux reported for the pane. */
    public java.nio.file.Path currentPath() {
        return state.currentPath();
    }

    /** The process id of the program running in the pane. */
    public long pid() {
        return state.pid();
    }

    /**
     * Grows this pane by a number of cells in one direction.
     *
     * @param direction which way to grow, named rather than flagged
     * @param cells how many terminal cells to grow by
     */
    public void resize(Direction direction, int cells) {
        if (cells < 1) {
            throw new IllegalArgumentException("cells is not positive: " + cells);
        }
        server.run(List.of("resize-pane", "-t", state.id().value(), direction.flag(), Integer.toString(cells)));
    }

    /** Which sides of its window the pane touches. */
    public PaneEdges edges() {
        return state.edges();
    }

    /** Puts this pane into copy mode, where its scrollback can be navigated. */
    public void copyMode() {
        server.run(List.of("copy-mode", "-t", state.id().value()));
    }

    /**
     * Which mode this pane is in at this moment, or empty when it is in none.
     *
     * <p>Read live rather than from the capture, because entering a mode is something this library
     * does and a caller wants to see the result of.
     *
     * @return tmux's own name for the mode, such as {@code copy-mode} or {@code tree-mode}
     */
    public Optional<String> mode() {
        String reported = expand("#{pane_mode}");
        return reported.isEmpty() ? Optional.empty() : Optional.of(reported);
    }

    /** Shows a clock in this pane. */
    public void clockMode() {
        server.run(List.of("clock-mode", "-t", state.id().value()));
    }

    /** Puts this pane into the session and window browser. */
    public void chooseTree() {
        server.run(List.of("choose-tree", "-t", state.id().value()));
    }

    /** Puts this pane into the option browser. */
    public void customizeMode() {
        server.run(List.of("customize-mode", "-t", state.id().value()));
    }

    /**
     * Puts this pane into the paste-buffer browser.
     *
     * <p>Does nothing when the server holds no buffers: tmux declines to show a chooser with nothing
     * in it, and says so by returning success and leaving the pane as it was. {@link #mode()} is how
     * a caller tells the two apart.
     */
    public void chooseBuffer() {
        server.run(List.of("choose-buffer", "-t", state.id().value()));
    }

    /** Puts this pane into the client browser, or leaves it alone when no client is attached. */
    public void chooseClient() {
        server.run(List.of("choose-client", "-t", state.id().value()));
    }

    /**
     * Puts this pane into the window browser, showing only the windows matching some text.
     *
     * <p>Despite the name, this finds nothing and goes nowhere: tmux opens the same tree the other
     * choosers use, narrowed to what matched, and leaves the active window where it was. A caller
     * wanting to act on a match should filter {@link Server#windows()} instead.
     *
     * <p>A match that finds nothing is not reported. The pane enters the browser either way, so
     * {@link #mode()} cannot tell the two apart and neither can an exit status.
     *
     * <p>Matches a window's name, its title and its visible content, which is what tmux does when
     * told nothing more specific.
     */
    public void findWindow(String match) {
        Objects.requireNonNull(match, "match");
        server.run(List.of("find-window", "-t", state.id().value(), match));
    }

    /** Narrows the window browser by name alone. See {@link #findWindow} for what it does not do. */
    public void findWindowByName(String match) {
        Objects.requireNonNull(match, "match");
        server.run(List.of("find-window", "-N", "-t", state.id().value(), match));
    }

    /**
     * Narrows the window browser by what the windows are showing.
     *
     * <p>See {@link #findWindow} for what it does not do.
     */
    public void findWindowByContent(String match) {
        Objects.requireNonNull(match, "match");
        server.run(List.of("find-window", "-C", "-t", state.id().value(), match));
    }

    /**
     * Leaves whichever mode this pane is in.
     *
     * <p>tmux spells this {@code copy-mode -q}, which quits any mode and not only copy mode — a clock
     * and a chooser go the same way. The name is tmux's history rather than its behaviour, so it is
     * not the one exposed here.
     */
    public void exitMode() {
        server.run(List.of("copy-mode", "-q", "-t", state.id().value()));
    }

    /** Makes this the active pane of its window. */
    public void select() {
        server.run(List.of("select-pane", "-t", state.id().value()));
    }

    /** Resizes this pane. */
    public void resizeTo(Dimensions size) {
        server.run(List.of(
                "resize-pane",
                "-t",
                state.id().value(),
                "-x",
                Integer.toString(size.width()),
                "-y",
                Integer.toString(size.height())));
    }

    /** The server this pane lives on. */
    public Server server() {
        return server;
    }

    /** The window link this pane was reached through. A pure read of the capture. */
    public Window window() {
        return snapshot.window(state.context())
                .map(window -> new Window(server, snapshot, window))
                .orElseThrow(() -> new LibTmuxException("the capture holds a pane whose window it never saw"));
    }

    /** This pane's own hooks. */
    public Hooks hooks() {
        return Hooks.pane(server, state.id());
    }

    /** This pane's own options. */
    public Options options() {
        return Options.pane(server, state.id());
    }

    /** This pane's visible content, one element per line. */
    public List<String> capture() {
        return capture(CaptureSpec.builder().build());
    }

    /**
     * Reads part of this pane, described by a lambda.
     *
     * <pre>{@code
     * List<String> everything = pane.capture(c -> c.fromStartOfHistory());
     * List<String> lastTen = pane.capture(c -> c.from(-10));
     * }</pre>
     *
     * @param configure receives a builder that reads the visible area and nothing else
     * @throws UnsupportedTmuxVersion if the spec asks for something this server does not have
     */
    public List<String> capture(Consumer<CaptureSpec.Builder> configure) {
        CaptureSpec.Builder builder = CaptureSpec.builder();
        configure.accept(builder);
        return capture(builder.build());
    }

    /**
     * Reads part of this pane according to a spec, which may be reused across panes.
     *
     * @throws UnsupportedTmuxVersion if the spec asks for something this server does not have
     */
    public List<String> capture(CaptureSpec spec) {
        return server.run(spec.argv(state.id().value(), server.version())).stdout();
    }

    /**
     * Sends keys to this pane without pressing Enter.
     *
     * <p>Separate from {@link #sendLine} rather than a boolean, so a call site says which it means.
     */
    public void send(String keys) {
        server.run(List.of("send-keys", "-t", state.id().value(), keys));
    }

    /** Sends a line to this pane and presses Enter, which is how a command gets run. */
    public void sendLine(String command) {
        server.run(List.of("send-keys", "-t", state.id().value(), command, "Enter"));
    }

    /**
     * Moves this pane into a window of its own, leaving tmux to name it.
     *
     * <p>On tmux 3.7 the name is supplied rather than left to tmux, because letting tmux choose it
     * there ends the server and every session on it. The name supplied is the one tmux would have
     * chosen, so the result is the same window either way.
     */
    public Window breakOut() {
        // The name tmux would have chosen anyway, supplied only so 3.7 does not have to choose it.
        // Read live rather than from the capture: a pane's command changes as its shell starts, and
        // a captured value can be older than the break by any amount.
        return breakNamed(Optional.empty(), currentCommandNow());
    }

    /** What tmux says is running here at this moment, rather than when the capture was taken. */
    private String currentCommandNow() {
        String reported = expand("#{pane_current_command}");
        return reported.isEmpty() ? state.currentCommand() : reported;
    }

    /**
     * Expands a tmux format in this pane's context, and answers with what it came to.
     *
     * <p>The way out of this library. Snapshots carry the fields worth carrying; a format reaches
     * everything else tmux knows, including fields added by a release this code has never heard of.
     *
     * <pre>{@code
     * String title = pane.expand("#{pane_title}");
     * String where = pane.expand("#{session_name}:#{window_index}.#{pane_index}");
     * }</pre>
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

    /**
     * Kills whatever runs here and starts the pane's default command again.
     *
     * <p>tmux refuses to respawn a pane that is still running something, on every supported release,
     * so what is asked for is always the killing form. Restarting a live process is the whole point
     * of the call.
     */
    public void respawn() {
        server.run(List.of("respawn-pane", "-k", "-t", state.id().value()));
    }

    /**
     * Kills whatever runs here and starts the given command instead.
     *
     * @param command the command and its arguments
     */
    public void respawn(String... command) {
        if (command.length == 0) {
            throw new IllegalArgumentException("command is empty");
        }
        List<String> argv =
                new ArrayList<>(List.of("respawn-pane", "-k", "-t", state.id().value()));
        argv.addAll(List.of(command));
        server.run(argv);
    }

    /**
     * Sends everything this pane prints to a shell command, until {@link #stopPiping}.
     *
     * <p>A second call replaces the first: tmux keeps one pipe per pane, not a list.
     *
     * @param shellCommand run by the user's shell, so it may redirect and pipe
     */
    public void pipeTo(String shellCommand) {
        Objects.requireNonNull(shellCommand, "shellCommand");
        server.run(List.of("pipe-pane", "-O", "-t", state.id().value(), shellCommand));
    }

    /** Stops sending this pane's output anywhere. Doing so twice is not an error. */
    public void stopPiping() {
        server.run(List.of("pipe-pane", "-t", state.id().value()));
    }

    /** Moves this pane into a window of its own with the given name. */
    public Window breakOut(String windowName) {
        Objects.requireNonNull(windowName, "windowName");
        return breakNamed(Optional.of(windowName), windowName);
    }

    /**
     * @param wanted the name the caller asked for, if they asked
     * @param supplied the name to hand tmux, which is never absent because 3.7 crashes without one
     */
    private Window breakNamed(Optional<String> wanted, String supplied) {
        List<String> argv = new ArrayList<>(List.of("break-pane", "-d", "-n", supplied));
        argv.addAll(List.of("-s", state.id().value(), "-P", "-F", BROKEN_OUT.template()));
        List<String> fields = BROKEN_OUT.split(server.run(argv).stdout().get(0));
        WindowContext created = new WindowContext(
                new SessionId(fields.get(0)),
                new WindowIndex(Integer.parseInt(fields.get(2))),
                new WindowId(fields.get(1)));
        if (server.version().equals(BREAK_PANE_NAMING_BROKEN)) {
            // 3.7 took the name and ignored it, so the caller's choice is applied afterwards.
            wanted.ifPresent(name -> server.run(List.of("rename-window", "-t", fields.get(1), name)));
        }
        ServerSnapshot fresh = server.snapshot();
        return fresh.window(created)
                .map(window -> new Window(server, fresh, window))
                .orElseThrow(() -> new ObjectDoesNotExist("the window just broken out is already gone"));
    }

    /**
     * Splits this pane in half, putting the new one below it.
     *
     * @return the pane that appeared
     */
    public Pane split() {
        return split(SplitSpec.builder().build());
    }

    /**
     * Splits this pane as described.
     *
     * <pre>{@code
     * Pane side = pane.split(s -> s.toRight().percent(30));
     * Pane app = pane.split(s -> s.running("htop").in(project));
     * }</pre>
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
     * Splits this pane according to a spec, which may be reused across panes.
     *
     * @return the pane that appeared
     * @throws UnsupportedTmuxVersion if the spec asks for something this server does not have
     */
    public Pane split(SplitSpec spec) {
        return created(server, spec.argv(state.id().value(), CREATED.template(), server.version()));
    }

    /**
     * Runs a command that reports one new pane, and hands back a handle on it.
     *
     * <p>The creating command names the pane it made, so the result is exact rather than whichever
     * pane a fresh listing happens to put last — two splits racing would otherwise be
     * indistinguishable.
     */
    static Pane created(Server server, List<String> argv) {
        List<String> reported = server.run(argv).stdout();
        if (reported.isEmpty()) {
            throw new LibTmuxException("tmux created a pane without reporting which");
        }
        PaneId id = new PaneId(CREATED.split(reported.get(0)).get(0));
        ServerSnapshot fresh = server.snapshot();
        return fresh.panes().stream()
                .filter(pane -> pane.id().equals(id))
                .findFirst()
                .map(pane -> new Pane(server, fresh, pane))
                .orElseThrow(() -> new ObjectDoesNotExist("the pane just created is already gone"));
    }

    /** The format a creating command reports its new pane through. */
    static String createdFormat() {
        return CREATED.template();
    }

    /** Pastes a named buffer into this pane, as though it had been typed. */
    public void paste(String bufferName) {
        server.run(List.of("paste-buffer", "-b", bufferName, "-t", state.id().value()));
    }

    /** Discards this pane's scrollback. */
    public void clearHistory() {
        server.run(List.of("clear-history", "-t", state.id().value()));
    }

    /** Swaps this pane's position with another's. */
    public void swapWith(Pane other) {
        server.run(
                List.of("swap-pane", "-s", state.id().value(), "-t", other.id().value()));
    }

    /** Moves this pane into another window, splitting it. */
    public void joinTo(Window window) {
        server.run(
                List.of("join-pane", "-s", state.id().value(), "-t", window.id().value()));
    }

    /** Closes this pane. */
    public void kill() {
        server.run(List.of("kill-pane", "-t", state.id().value()));
    }

    /**
     * Takes a new capture and returns this pane as it is now.
     *
     * @throws ObjectDoesNotExist if the pane is gone
     */
    public Pane refresh() {
        ServerSnapshot fresh = server.snapshot();
        return fresh.panes().stream()
                .filter(pane -> pane.id().equals(state.id()))
                .findFirst()
                .map(pane -> new Pane(server, fresh, pane))
                .orElseThrow(() -> new ObjectDoesNotExist("pane " + state.id() + " no longer exists"));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Pane that
                && server.identity().equals(that.server.identity())
                && state.id().equals(that.state.id());
    }

    @Override
    public int hashCode() {
        return Objects.hash(server.identity(), state.id());
    }

    @Override
    public String toString() {
        return "Pane[" + state.id() + " " + state.currentCommand() + "]";
    }
}
