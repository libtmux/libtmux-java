package io.github.libtmux;

import com.google.errorprone.annotations.CheckReturnValue;
import io.github.libtmux.batch.Batch;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.snapshot.PaneState;
import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.snapshot.WindowContext;
import io.github.libtmux.transport.TmuxTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One tmux pane, as one capture saw it.
 *
 * <p>Identity is the server and the pane id. An index is state: panes renumber as neighbours come
 * and go.
 */
public final class Pane {

    /**
     * How often a wait looks again.
     *
     * <p>Short enough that a wait reports promptly, long enough that a wait held open for minutes
     * is not thousands of tmux invocations. A caller who needs an exact moment wants a signal on a
     * {@link Channel}, not a shorter interval here.
     */
    private static final Duration POLL = Duration.ofMillis(50);

    /**
     * The least a wait's first read is given, and the budget for telling a dead server from a read
     * that failed.
     *
     * <p>A quarter of a second, what {@link Channel#drain} gives a pending signal to come straight
     * back: long enough for tmux to answer, short enough not to be a wait.
     */
    private static final Duration SHORTEST_READ = Duration.ofMillis(250);

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
    public Optional<Boolean> floating() {
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
    public Path currentPath() {
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
        server.run(
                snapshot, List.of("resize-pane", "-t", state.id().value(), direction.flag(), Integer.toString(cells)));
    }

    /** Which sides of its window the pane touches. */
    public PaneEdges edges() {
        return state.edges();
    }

    /** Puts this pane into copy mode, where its scrollback can be navigated. */
    public void copyMode() {
        server.run(snapshot, List.of("copy-mode", "-t", state.id().value()));
    }

    /**
     * Which mode this pane is in at this moment, or empty when it is in none.
     *
     * <p>Read live rather than from the capture, because entering a mode is something this library
     * does and a caller wants to see the result of.
     *
     * @return the mode, or empty when the pane is showing its program
     * @throws LibTmuxException if this tmux named a mode outside the supported range
     */
    public Optional<PaneMode> mode() {
        String reported = expand("#{pane_mode}");
        return reported.isEmpty() ? Optional.empty() : Optional.of(PaneMode.of(reported));
    }

    /** Shows a clock in this pane. */
    public void clockMode() {
        server.run(snapshot, List.of("clock-mode", "-t", state.id().value()));
    }

    /** Puts this pane into the session and window browser. */
    public void chooseTree() {
        server.run(snapshot, List.of("choose-tree", "-t", state.id().value()));
    }

    /** Puts this pane into the option browser. */
    public void customizeMode() {
        server.run(snapshot, List.of("customize-mode", "-t", state.id().value()));
    }

    /**
     * Puts this pane into the paste-buffer browser.
     *
     * <p>Does nothing when the server holds no buffers: tmux declines to show a chooser with nothing
     * in it, and says so by returning success and leaving the pane as it was. {@link #mode()} is how
     * a caller tells the two apart.
     */
    public void chooseBuffer() {
        server.run(snapshot, List.of("choose-buffer", "-t", state.id().value()));
    }

    /** Puts this pane into the client browser, or leaves it alone when no client is attached. */
    public void chooseClient() {
        server.run(snapshot, List.of("choose-client", "-t", state.id().value()));
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
        findWindow(FindSpec.builder().matching(match).build());
    }

    /**
     * Puts this pane into the window browser, narrowed as described.
     *
     * <pre>{@code
     * pane.findWindow(f -> f.matching("build").inName());
     * }</pre>
     *
     * @param configure receives a builder that looks everywhere tmux looks by default
     */
    public void findWindow(Consumer<FindSpec.Builder> configure) {
        FindSpec.Builder builder = FindSpec.builder();
        configure.accept(builder);
        findWindow(builder.build());
    }

    /** Puts this pane into the window browser, narrowed by a spec that may be reused. */
    public void findWindow(FindSpec spec) {
        server.run(snapshot, spec.argv(state.id().value()));
    }

    /**
     * Leaves whichever mode this pane is in.
     *
     * <p>tmux spells this {@code copy-mode -q}, which quits any mode and not only copy mode — a clock
     * and a chooser go the same way. The name is tmux's history rather than its behaviour, so it is
     * not the one exposed here.
     */
    public void exitMode() {
        server.run(snapshot, List.of("copy-mode", "-q", "-t", state.id().value()));
    }

    /** Makes this the active pane of its window. */
    public void select() {
        server.run(snapshot, List.of("select-pane", "-t", state.id().value()));
    }

    /**
     * Retitles this pane and returns its replacement capture.
     *
     * <p>Retain the result to read the changed title. This handle keeps its original captured state.
     *
     * <p>A program running in the pane can set its own title through an escape sequence, and tmux
     * reports that one instead. This says what the title is now, not what it will stay.
     */
    @CheckReturnValue
    public Pane retitle(String title) {
        Objects.requireNonNull(title, "title");
        server.run(snapshot, List.of("select-pane", "-t", state.id().value(), "-T", TmuxFormats.literal(title)));
        return refresh();
    }

    /** Resizes this pane. */
    public void resizeTo(Dimensions size) {
        server.run(
                snapshot,
                List.of(
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

    /** Collects commands fenced to the server incarnation that produced this pane. */
    public Batch batch() {
        return server.batch(snapshot);
    }

    ServerSnapshot snapshot() {
        return snapshot;
    }

    /** The window link this pane was reached through. A pure read of the capture. */
    public Window window() {
        return snapshot.window(state.context())
                .map(window -> new Window(server, snapshot, window))
                .orElseThrow(() -> new LibTmuxException("the capture holds a pane whose window it never saw"));
    }

    /** This pane's own hooks. */
    public Hooks hooks() {
        return Hooks.pane(server, snapshot, state.id());
    }

    /** This pane's own options. */
    public Options options() {
        return Options.pane(server, snapshot, state.id());
    }

    /** This pane's visible content, one element per line. */
    public List<String> capture() {
        return capture(CaptureSpec.builder().build());
    }

    /**
     * Waits until this pane's text contains something, and says why the wait ended.
     *
     * <p><strong>Reach for this last.</strong> It reads the screen on a timer, which is a heuristic
     * about a program's output rather than a fact about it, and the cheaper waits are exact:
     *
     * <ol>
     *   <li>If you wrote the command, append {@code ; tmux wait-for -S name} to it and block on
     *       {@link Server#channel}. tmux blocks server-side and returns on the signal itself, so
     *       nothing is inferred from the screen and one tmux process covers the whole wait. That is
     *       the only deterministic wait here, and it is the right one whenever the command is yours.
     *   <li>If you did not write the command — a daemon printing that it is ready, a build somebody
     *       else started — this is the case polling is for.
     * </ol>
     *
     * <p>The timeout bounds the whole wait, reads included. Each read is given only what is left of
     * it, so a slow tmux cannot stretch a short wait out to the server's default deadline, and text
     * that first appears after the deadline is not reported. The first read alone is allowed at least
     * a quarter of a second, so a timeout shorter than one read can still answer that the text is
     * already there.
     *
     * <p>Waiting longer is also less reliable, not more: tmux frees the oldest scrollback once
     * {@code history-limit} is reached, so a long wait on a productive pane can end up reading past
     * the lines it was watching for.
     *
     * @param text the text to wait for, matched anywhere in a captured line
     * @param timeout how long to keep looking
     * @return {@link WakeReason#SIGNALLED} when the text appeared, {@link WakeReason#TIMED_OUT} when
     *     it did not, {@link WakeReason#SERVER_GONE} when the server went away underneath the wait
     * @throws LibTmuxException if a read fails while the server is still answering — most often
     *     because this pane was killed, which is not a timeout
     * @throws InterruptedException if the waiting thread is interrupted, which is a cancellation
     *     rather than a timeout and so is not reported as one
     */
    public WakeReason awaitText(String text, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(text, "text");
        return awaitCondition(bounded -> bounded.capture().stream().anyMatch(line -> line.contains(text)), timeout);
    }

    /**
     * Waits until a condition holds for this pane as tmux reports it, and says why the wait ended.
     *
     * <p>The pane is captured again before each test, so the condition reads fresh state rather than
     * the capture this handle was built from. What the condition receives is an ordinary handle on
     * this pane's server and can be kept. The ordering {@link #awaitText} describes applies here too —
     * a signal on a {@link Server#channel} is exact, and this is for when nothing signals — and so do
     * its deadline rules.
     *
     * <pre>{@code
     * pane.await(fresh -> !fresh.currentCommand().equals("zsh"), Duration.ofSeconds(5));
     * }</pre>
     *
     * @param settled receives this pane as it is now
     * @param timeout how long to keep looking
     * @return why the wait ended
     * @throws ObjectDoesNotExistException if this pane is killed while its server stays up, which is not a
     *     timeout
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public WakeReason await(Predicate<Pane> settled, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(settled, "settled");
        return awaitCondition(bounded -> settled.test(bounded.refresh().through(server)), timeout);
    }

    /**
     * One deadline for both public waits, applied to the reads as well as to the gaps between them.
     *
     * <p>Each read goes through {@link Server#within} with what is left of the deadline, so a read
     * cannot outlast the wait. A read that runs out of that budget has reached the wait's own
     * deadline, and is a timeout. Once the deadline has passed no further read starts, which is what
     * keeps text that arrives late from being reported.
     *
     * <p>An ordinary timeout asks tmux nothing more. The last read answered, so the server was there
     * a poll interval ago, and a liveness probe after the deadline would only spend time the caller
     * did not give. A read that <em>failed</em> is different: tmux reports "no server" and "no such
     * pane" the same way, so that one gets a second look to tell {@link WakeReason#SERVER_GONE} from a
     * failure that belongs to the caller.
     */
    private WakeReason awaitCondition(Predicate<Pane> poll, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout is negative: " + timeout);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean first = true;
        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException(
                        "interrupted while waiting on pane " + state.id().value());
            }
            Duration left = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
            Duration budget = first && left.compareTo(SHORTEST_READ) < 0 ? SHORTEST_READ : left;
            first = false;
            try {
                if (poll.test(through(server.within(budget)))) {
                    return WakeReason.SIGNALLED;
                }
            } catch (TmuxTimeoutException expired) {
                return WakeReason.TIMED_OUT;
            } catch (LibTmuxException unreadable) {
                return afterFailedRead(unreadable);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return WakeReason.TIMED_OUT;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(POLL.toNanos(), remaining));
            if (System.nanoTime() >= deadline) {
                return WakeReason.TIMED_OUT;
            }
        }
    }

    /**
     * Tells a server that went away from a read that failed for a reason of the caller's.
     *
     * <p>A probe that cannot get an answer in time proves nothing either way, so the original failure
     * is what the caller sees, carrying the probe's.
     */
    private WakeReason afterFailedRead(LibTmuxException unreadable) {
        boolean alive;
        try {
            alive = server.isAlive(SHORTEST_READ);
        } catch (TmuxTimeoutException unanswered) {
            unreadable.addSuppressed(unanswered);
            throw unreadable;
        }
        if (alive) {
            throw unreadable;
        }
        return WakeReason.SERVER_GONE;
    }

    /** This pane's captured state, addressed through another view of the same server. */
    private Pane through(Server via) {
        return new Pane(via, snapshot, state);
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
     * @throws UnsupportedTmuxVersionException if the spec asks for something this server does not have
     */
    public List<String> capture(Consumer<CaptureSpec.Builder> configure) {
        CaptureSpec.Builder builder = CaptureSpec.builder();
        configure.accept(builder);
        return capture(builder.build());
    }

    /**
     * Reads part of this pane according to a spec, which may be reused across panes.
     *
     * @throws UnsupportedTmuxVersionException if the spec asks for something this server does not have
     */
    public List<String> capture(CaptureSpec spec) {
        return server.run(snapshot, spec.argv(state.id().value(), server.version(snapshot)))
                .stdout();
    }

    /**
     * Sends keys to this pane without pressing Enter.
     *
     * <p>Separate from {@link #sendLine} rather than a boolean, so a call site says which it means.
     */
    public void send(String keys) {
        server.run(snapshot, List.of("send-keys", "-t", state.id().value(), keys));
    }

    /**
     * Sends an ordered group of key names to this pane, as tmux resolves them.
     *
     * <p>Each entry is a key name, so {@code C-c} interrupts rather than typing three characters.
     * {@link #sendLiteral} is the other reading of the same list; they are separate methods for the
     * reason {@link #send} and {@link #sendLine} are.
     */
    public void sendKeys(List<String> keys) {
        server.run(snapshot, sendKeysArgv(keys, false));
    }

    /**
     * Sends an ordered group of strings to this pane as the characters they spell.
     *
     * <p>Nothing is resolved as a key name, so {@code C-c} types those three characters.
     */
    public void sendLiteral(List<String> keys) {
        server.run(snapshot, sendKeysArgv(keys, true));
    }

    private List<String> sendKeysArgv(List<String> keys, boolean literal) {
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("keys are empty");
        }
        List<String> argv = new ArrayList<>(List.of("send-keys"));
        if (literal) {
            argv.add("-l");
        }
        argv.addAll(List.of("-t", state.id().value(), "--"));
        argv.addAll(keys);
        return argv;
    }

    /** Sends a line to this pane and presses Enter, which is how a command gets run. */
    public void sendLine(String command) {
        Objects.requireNonNull(command, "command");
        server.run(snapshot, List.of("send-keys", "-l", "-t", state.id().value(), "--", command + "\r"));
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
     * @return the expansion, whole when it spans lines and empty when the format expanded to
     *     nothing
     */
    public String expand(String format) {
        Objects.requireNonNull(format, "format");
        List<String> reported = server.run(
                        snapshot,
                        List.of("display-message", "-p", "-t", state.id().value(), format))
                .stdout();
        return String.join("\n", reported);
    }

    /** Reads validated tmux variables in this pane's format context. */
    public Map<String, String> variables(List<String> names) {
        return server.variables(names, this::expand);
    }

    /**
     * Kills whatever runs here and starts the pane's default command again.
     *
     * <p>tmux refuses to respawn a pane that is still running something, on every supported release,
     * so what is asked for is always the killing form. Restarting a live process is the whole point
     * of the call.
     */
    public void respawn() {
        server.run(snapshot, List.of("respawn-pane", "-k", "-t", state.id().value()));
    }

    /** Restarts the configured pane process in a caller-supplied literal directory. */
    public void respawnIn(Path directory) {
        Objects.requireNonNull(directory, "directory");
        server.run(snapshot, respawnArgv(state.id(), directory));
    }

    static List<String> respawnArgv(PaneId pane, Path directory) {
        return List.of("respawn-pane", "-k", "-c", TmuxFormats.literal(directory.toString()), "-t", pane.value());
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
        server.run(snapshot, argv);
    }

    /**
     * Sends everything this pane prints to a shell command, until {@link #stopPiping}.
     *
     * <p>A second call replaces the first: tmux keeps one pipe per pane, not a list.
     *
     * @param shellCommand run by the user's shell, so it may redirect and pipe
     *
     * <p>tmux expands {@code #(...)} in this command before a shell sees it, and shell quoting does
     * not prevent that. Pass any interpolated value through {@link TmuxFormats#literal} unless you
     * mean it to be expanded.
     */
    public void pipeTo(String shellCommand) {
        Objects.requireNonNull(shellCommand, "shellCommand");
        server.run(snapshot, List.of("pipe-pane", "-O", "-t", state.id().value(), shellCommand));
    }

    /** Stops sending this pane's output anywhere. Doing so twice is not an error. */
    public void stopPiping() {
        server.run(snapshot, List.of("pipe-pane", "-t", state.id().value()));
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
        List<String> fields =
                BROKEN_OUT.split(server.run(snapshot, argv).stdout().get(0));
        WindowContext created = new WindowContext(
                new SessionId(fields.get(0)),
                new WindowIndex(Integer.parseInt(fields.get(2))),
                new WindowId(fields.get(1)));
        if (server.version(snapshot).equals(BREAK_PANE_NAMING_BROKEN)) {
            // 3.7 took the name and ignored it, so the caller's choice is applied afterwards.
            wanted.ifPresent(name ->
                    server.run(snapshot, List.of("rename-window", "-t", fields.get(1), TmuxFormats.literal(name))));
        }
        ServerSnapshot fresh = server.refresh(snapshot);
        return fresh.window(created)
                .map(window -> new Window(server, fresh, window))
                .orElseThrow(() -> new ObjectDoesNotExistException("the window just broken out is already gone"));
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
     * @throws UnsupportedTmuxVersionException if the spec asks for something this server does not have
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
     * @throws UnsupportedTmuxVersionException if the spec asks for something this server does not have
     */
    public Pane split(SplitSpec spec) {
        return created(server, snapshot, spec.argv(state.id().value(), CREATED.template(), server.version(snapshot)));
    }

    /**
     * Runs a command that reports one new pane, and hands back a handle on it.
     *
     * <p>The creating command names the pane it made, so the result is exact rather than whichever
     * pane a fresh listing happens to put last — two splits racing would otherwise be
     * indistinguishable.
     */
    static Pane created(Server server, ServerSnapshot previous, List<String> argv) {
        List<String> reported = server.run(previous, argv).stdout();
        if (reported.isEmpty()) {
            throw new LibTmuxException("tmux created a pane without reporting which");
        }
        PaneId id = new PaneId(CREATED.split(reported.get(0)).get(0));
        ServerSnapshot fresh = server.refresh(previous);
        return fresh.panes().stream()
                .filter(pane -> pane.id().equals(id))
                .findFirst()
                .map(pane -> new Pane(server, fresh, pane))
                .orElseThrow(() -> new ObjectDoesNotExistException("the pane just created is already gone"));
    }

    /** The format a creating command reports its new pane through. */
    static String createdFormat() {
        return CREATED.template();
    }

    /** Pastes a named buffer into this pane, as though it had been typed. */
    public void pasteBuffer(String name) {
        Objects.requireNonNull(name, "name");
        server.run(
                snapshot, List.of("paste-buffer", "-b", name, "-t", state.id().value()));
    }

    /**
     * Pastes text into this pane as one block, leaving nothing behind on the server.
     *
     * <p>Nothing in the text is looked up as a key name, so a line containing {@code Enter} or a
     * bracket arrives as those characters. That is what an editor, a REPL, or anything reading a
     * here-document needs, and it is the difference from {@link #send}.
     *
     * <p>tmux needs a buffer to paste from, and this one travels in the same invocation as the paste
     * that consumes it. A caller that stops in between therefore cannot leave the text in the paste
     * history every session on the server can read.
     *
     * <p>The text goes to tmux on standard input rather than as an argument, so it is not bounded by
     * the size of a tmux command and never reaches tmux's parser.
     *
     * @throws IllegalArgumentException if the text contains NUL, which a terminal cannot receive and
     *     {@link #send} refuses too
     * @throws UnsupportedTmuxVersionException before tmux 3.4, where deleting the buffer left by a failed
     *     paste can remove one this did not create
     */
    public void paste(String text) {
        Objects.requireNonNull(text, "text");
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("pasted text cannot contain NUL");
        }
        TmuxVersion running = server.version(snapshot);
        if (!running.atLeast(Buffers.EXACT_NAMED_DELETE)) {
            throw new UnsupportedTmuxVersionException("pasting text", Buffers.EXACT_NAMED_DELETE, running);
        }
        String buffer = "libtmux-paste-" + UUID.randomUUID();
        try {
            server.runTogether(
                    snapshot,
                    text,
                    List.of(
                            List.of("load-buffer", "-b", buffer, "-"),
                            // -d removes the buffer as it pastes, so the success path leaves nothing
                            // even when this is the last thing the caller manages to run.
                            List.of(
                                    "paste-buffer",
                                    "-d",
                                    "-b",
                                    buffer,
                                    "-t",
                                    state.id().value())));
        } catch (RuntimeException failure) {
            try {
                server.buffers().delete(buffer);
            } catch (RuntimeException ignored) {
                // Already gone, or the server is; neither changes what the caller is told.
            }
            throw failure;
        }
    }

    /**
     * As {@link #paste(String)}, after a caller rechecks state once its private buffer is ready.
     *
     * <p>If the check throws, the text is not pasted and the private buffer is removed.
     *
     * @param beforePaste runs after staging and immediately before the paste dispatch
     */
    public void paste(String text, Runnable beforePaste) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(beforePaste, "beforePaste");
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("pasted text cannot contain NUL");
        }
        TmuxVersion running = server.version(snapshot);
        if (!running.atLeast(Buffers.EXACT_NAMED_DELETE)) {
            throw new UnsupportedTmuxVersionException("pasting text", Buffers.EXACT_NAMED_DELETE, running);
        }
        String buffer = "libtmux-paste-" + UUID.randomUUID();
        try {
            server.runTogether(snapshot, text, List.of(List.of("load-buffer", "-b", buffer, "-")));
            beforePaste.run();
            server.run(
                    snapshot,
                    List.of("paste-buffer", "-d", "-b", buffer, "-t", state.id().value()));
        } catch (RuntimeException failure) {
            try {
                server.buffers().delete(buffer);
            } catch (RuntimeException ignored) {
                // Already gone, or the server is; neither changes what the caller is told.
            }
            throw failure;
        }
    }

    /** Discards this pane's scrollback. */
    public void clearHistory() {
        server.run(snapshot, List.of("clear-history", "-t", state.id().value()));
    }

    /** Swaps this pane's position with another's. */
    public void swapWith(Pane other) {
        Objects.requireNonNull(other, "other");
        server.requireSameIncarnation(snapshot, other.server(), other.snapshot());
        server.run(
                snapshot,
                List.of("swap-pane", "-s", state.id().value(), "-t", other.id().value()));
    }

    /** Moves this pane into another window, splitting it. */
    public void joinTo(Window window) {
        Objects.requireNonNull(window, "window");
        server.requireSameIncarnation(snapshot, window.server(), window.snapshot());
        server.run(
                snapshot,
                List.of("join-pane", "-s", state.id().value(), "-t", window.id().value()));
    }

    /** Closes this pane. */
    public void kill() {
        server.run(snapshot, List.of("kill-pane", "-t", state.id().value()));
    }

    /**
     * Takes a new capture and returns this pane as it is now.
     *
     * <p>This handle remains unchanged. Use the returned handle for subsequent state reads.
     *
     * @throws ObjectDoesNotExistException if the pane is gone
     */
    @CheckReturnValue
    public Pane refresh() {
        ServerSnapshot fresh = server.refresh(snapshot);
        return fresh.panes().stream()
                .filter(pane -> pane.id().equals(state.id()))
                .findFirst()
                .map(pane -> new Pane(server, fresh, pane))
                .orElseThrow(() -> new ObjectDoesNotExistException("pane " + state.id() + " no longer exists"));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Pane that
                && server.identity(snapshot).equals(that.server.identity(that.snapshot))
                && state.id().equals(that.state.id());
    }

    @Override
    public int hashCode() {
        return Objects.hash(server.identity(snapshot), state.id());
    }

    @Override
    public String toString() {
        return "Pane[" + state.id() + " " + state.currentCommand() + "]";
    }
}
