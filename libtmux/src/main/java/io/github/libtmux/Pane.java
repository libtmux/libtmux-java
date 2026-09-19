package io.github.libtmux;

import com.google.errorprone.annotations.CheckReturnValue;
import io.github.libtmux.batch.Batch;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.snapshot.PaneState;
import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.snapshot.WindowContext;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.TmuxTimeoutException;
import io.github.libtmux.transport.TmuxTransportException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
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

    private static final Duration SHORTEST_POLL = Duration.ofMillis(10);

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
     * that is given. Both are one inverted check in {@code cmd-break-pane.c} — {@code if (name !=
     * NULL)} where it meant {@code == NULL} - fixed by {@code 84291b02}, which {@code git tag
     * --contains} places on 3.7a and nothing earlier, matching CHANGES' "3.7 TO 3.7a": "Fix crash in
     * break-pane when no name is provided." Not probeable: break-pane's args are unchanged across the
     * fix, so nothing in {@link Server#listCommands} distinguishes the two releases.
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

    /** Where the pane's top-left corner sat in its window when captured, in terminal cells. */
    public PanePosition position() {
        return state.position();
    }

    /** The pane title, which a program running inside it can change. */
    public String title() {
        return state.title();
    }

    /**
     * The working directory tmux reported for the pane.
     *
     * <p>Converted on request rather than when the pane was captured, because a directory name is
     * bytes to tmux and this JVM cannot always represent one: converting at capture time would lose
     * every pane on the server over a single pane's directory. {@link #currentPathText()} is the
     * value tmux actually reported, and never throws.
     *
     * @throws LibTmuxException if this JVM's encoding cannot represent the name, which a UTF-8
     *     locale fixes
     */
    public Path currentPath() {
        String reported = state.currentPath();
        try {
            return Path.of(reported);
        } catch (InvalidPathException unrepresentable) {
            throw new LibTmuxException(
                    "this JVM cannot represent the directory of pane " + state.id()
                            + " as a path; start the JVM in a UTF-8 locale (LC_ALL=C.UTF-8), or read"
                            + " currentPathText()",
                    unrepresentable);
        }
    }

    /** The working directory tmux reported for the pane, exactly as tmux reported it. */
    public String currentPathText() {
        return state.currentPath();
    }

    /**
     * The process id of the program running in the pane, captured at the time of the read this
     * handle came from.
     *
     * <p>Empty means tmux reported no process — never a literal {@code 0}, which would be
     * indistinguishable from a real pid. That covers two different situations this capture cannot
     * tell apart: a pane that never ran one ({@link SplitSpec.Builder#empty()}) and, from tmux 3.8, a
     * pane that ran one and it has since died. Before 3.8 a dead pane still reports its stale pid.
     * {@link #dead()} is the live read that tells a caller which.
     */
    public OptionalLong pid() {
        return state.pid();
    }

    /**
     * Whether this pane's process has exited, read fresh rather than from the capture.
     *
     * <p>Dying is something that happens between reads, so a cached answer would report a pane that
     * died after this handle was captured as alive forever — the trap a snapshot-backed {@code
     * isDead()} would set, which is why there is no such accessor on the capture itself. This asks
     * tmux directly instead, every time.
     *
     * <p>A dead pane is not necessarily a gone one: {@code remain-on-exit} keeps it around, still
     * listed, to be read. A pane with no {@code remain-on-exit} is simply removed once its process
     * exits — {@link #refresh()} on this handle then throws {@link ObjectDoesNotExistException}, and
     * this method throws too. Unlike every other command {@code Pane} sends, {@code display-message
     * -t} does not error on a target it cannot resolve — it exits 0 with nothing on stdout — so a
     * gone pane looks like a live one that answered nothing rather than like a failure. That empty
     * read, and not a "can't find pane" from tmux, is what this method throws on: a real pane always
     * answers {@code 0} or {@code 1} for this format, never nothing.
     *
     * @throws ObjectDoesNotExistException if the pane is gone from a server that still answers
     */
    public boolean dead() {
        String value = expand("#{pane_dead}");
        if (value.isEmpty()) {
            throw new ObjectDoesNotExistException("pane " + state.id() + " no longer exists");
        }
        return "1".equals(value);
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
     * What the pane has shown that this library did not type, as whole lines.
     *
     * <p>What a wait has to read, and different from {@link #capture()} twice over. Wrapped rows are
     * rejoined, because a terminal breaks a long line wherever the pane happens to end and text
     * split across that break is in no single row — a wait watching for it would never see it while
     * it sits in plain view. And an echo of what this library just typed is taken out, because a
     * terminal shows the caller's own command back and a wait for something that command's text
     * contains would otherwise be answered by the question.
     */
    private List<String> shown(TypedText typed) {
        return typed.withoutEcho(capture(CaptureSpec.Builder::joiningWrappedLines));
    }

    /**
     * Notes text that reached this pane without going through this handle, so a wait discounts its
     * echo the way it discounts text sent through {@link #sendLiteral}.
     *
     * <p>Use this for input delivered outside this handle. Input sent through {@link #sendKeys}
     * or {@link #sendLiteral} already records synchronized recipients. Include a trailing line break
     * when the external write submitted its line; otherwise the text remains pending.
     */
    public void noteTyped(String text) {
        Objects.requireNonNull(text, "text");
        server.echo().recordLiteral(identity(), state.id(), text).confirm();
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
     * a quarter of a second, so a timeout shorter than one read can still answer
     * {@link TextOutcome#PRESENT_AT_ENTRY}.
     *
     * <p>Waiting longer is also less reliable, not more: tmux frees the oldest scrollback once
     * {@code history-limit} is reached, so a long wait on a productive pane can end up reading past
     * the lines it was watching for.
     *
     * @param text the text to wait for, matched anywhere in a captured line
     * @param timeout how long to keep looking
     * @return why the wait ended, which tells text that appeared from text that was already there
     * @throws LibTmuxException if a read fails while the server is still answering — most often
     *     because this pane was killed, which is not a timeout
     * @throws InterruptedException if the waiting thread is interrupted, which is a cancellation
     *     rather than a timeout and so is not reported as one
     */
    public TextOutcome awaitText(String text, Duration timeout) throws InterruptedException {
        return awaitText(text, timeout, POLL);
    }

    /**
     * As {@link #awaitText(String, Duration)}, looking every {@code every} rather than every 50 ms.
     *
     * <p>Each look is a tmux process, so the interval is a trade the caller can see: a wait for a
     * ten-minute build looking every 50 ms starts twelve thousand of them, and one looking every two
     * seconds starts three hundred and notices up to two seconds late.
     *
     * @param every how long to leave between looks, at least 10 ms
     * @throws IllegalArgumentException if {@code every} is shorter than 10 ms
     */
    public TextOutcome awaitText(String text, Duration timeout, Duration every) throws InterruptedException {
        Objects.requireNonNull(text, "text");
        TypedText typed = TypedText.in(this);
        boolean[] reading = {true, false};
        WakeReason ended = awaitCondition(
                bounded -> {
                    boolean found = bounded.shown(typed).stream().anyMatch(line -> line.contains(text));
                    if (reading[0]) {
                        reading[0] = false;
                        reading[1] = found;
                    }
                    return found;
                },
                timeout,
                every);
        return switch (ended) {
            case SIGNALLED -> reading[1] ? TextOutcome.PRESENT_AT_ENTRY : TextOutcome.APPEARED;
            case TIMED_OUT -> TextOutcome.TIMED_OUT;
            case SERVER_GONE -> TextOutcome.SERVER_GONE;
        };
    }

    /**
     * Runs a shell command in this pane to its end, and answers with its exit status and output.
     *
     * <p><strong>Reach for this first</strong> whenever the command is yours. Nothing is inferred from
     * the screen: the command runs in a subshell of the pane's own shell, whose trap reports {@code $?}
     * and signals a private {@code wait-for} channel, so the wait is tmux's and the status is the
     * shell's. The trap covers interrupt and terminate as well as exit, so a command someone stops at
     * the pane ends this call with the status it was stopped with rather than leaving it waiting. The output is what the command printed and nothing else — not the typed line,
     * not the prompt — cut out between two markers the plumbing prints around it.
     *
     * <pre>{@code
     * PaneRun built = pane.run("make test", Duration.ofMinutes(5));
     * if (!built.succeeded()) {
     *     System.err.println(String.join("\n", built.output()));
     * }
     * }</pre>
     *
     * <p>It runs in the shell a person set up — its directory, its environment, a virtualenv someone
     * activated — which is the reason to type it there rather than start a process of your own. The
     * pane has to be running a POSIX shell for the typed line to mean what it says.
     *
     * <p>A command still running at the deadline keeps running; the answer carries what it had printed
     * by then, and {@link PaneRun#exact()} is false.
     *
     * @param command a line of shell, run as {@code eval} would run it
     * @param timeout how long to wait for it to end
     * @throws IllegalStateException if the pane is not running a POSIX shell
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public PaneRun run(String command, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        // What the pane is running and where its server listens, read together: one command, and the
        // same moment, so the shell checked is the shell the line is typed into.
        Map<String, String> here = variables(List.of("pane_current_command", "socket_path"));
        String running = here.getOrDefault("pane_current_command", "");
        PaneCommand.requirePosixShell(running.isEmpty() ? state.currentCommand() : running);
        PaneCommand frame = PaneCommand.fresh();
        List<String> tmux = List.of(server.config().binaryPath(), "-S", here.getOrDefault("socket_path", ""));
        sendLine(frame.typed(tmux, command));

        WakeReason woke = server.channel(frame.channel()).await(timeout);
        if (woke == WakeReason.SERVER_GONE) {
            return new PaneRun(PaneRun.Outcome.SERVER_GONE, OptionalInt.empty(), List.of(), false);
        }
        // Rows rejoined, so a line the command printed wider than the pane comes back as it printed it,
        // and the typed line — which wraps — is one line that holds the markers without equalling one.
        PaneCommand.Framed framed =
                frame.frame(capture(spec -> spec.fromStartOfHistory().joiningWrappedLines()));
        return woke == WakeReason.SIGNALLED
                ? new PaneRun(PaneRun.Outcome.FINISHED, framed.status(), framed.lines(), framed.exact())
                : new PaneRun(PaneRun.Outcome.TIMED_OUT, OptionalInt.empty(), framed.lines(), false);
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
        return await(settled, timeout, POLL);
    }

    /**
     * As {@link #await(Predicate, Duration)}, looking every {@code every} rather than every 50 ms.
     *
     * @param every how long to leave between looks, at least 10 ms
     * @throws IllegalArgumentException if {@code every} is shorter than 10 ms
     */
    public WakeReason await(Predicate<Pane> settled, Duration timeout, Duration every) throws InterruptedException {
        Objects.requireNonNull(settled, "settled");
        return awaitCondition(bounded -> settled.test(bounded.refresh().through(server)), timeout, every);
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
    private WakeReason awaitCondition(Predicate<Pane> poll, Duration timeout, Duration every)
            throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(every, "every");
        if (every.compareTo(SHORTEST_POLL) < 0) {
            throw new IllegalArgumentException("looking more often than every " + SHORTEST_POLL.toMillis()
                    + " ms spends a tmux process per look for no reading a person could tell apart: " + every);
        }
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
            TimeUnit.NANOSECONDS.sleep(Math.min(every.toNanos(), remaining));
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
     *
     * <p>Recorded as an echo before dispatch, through the same key model {@link #sendKeys} uses,
     * because tmux types anything here that is not one of its key names and a terminal echoes what
     * is typed.
     */
    public void send(String keys) {
        sendKeys(List.of(keys));
    }

    /**
     * Sends an ordered group of key names to this pane, as tmux resolves them.
     *
     * <p>Each entry is resolved through tmux's key table; an unknown name is typed as text.
     * Recorded input includes effective synchronized recipients. Known dispatch failures undo the
     * record; uncertain delivery retains it. {@link #sendLiteral} types every entry literally.
     */
    public void sendKeys(List<String> keys) {
        sendKeys(keys, () -> {});
    }

    /**
     * As {@link #sendKeys(List)}, after a caller validates the prepared input destination.
     *
     * <p>The callback runs after recipient discovery and immediately before recording and dispatch.
     * If it throws, no keys are sent and no echo is recorded.
     *
     * @param beforeSend validates current input ownership
     */
    public void sendKeys(List<String> keys, Runnable beforeSend) {
        Objects.requireNonNull(beforeSend, "beforeSend");
        List<String> argv = sendKeysArgv(keys, false);
        List<PaneId> recipients = keyRecipients();
        beforeSend.run();
        List<PaneEcho.Recorded> recorded = recipients.stream()
                .map(id -> server.echo().recordKeys(identity(), id, keys))
                .toList();
        try {
            server.run(snapshot, argv);
        } catch (RuntimeException failure) {
            recorded.forEach(record -> settleFailure(record, failure));
            throw failure;
        }
        recorded.forEach(PaneEcho.Recorded::confirm);
    }

    /**
     * Sends an ordered group of strings to this pane as the characters they spell.
     *
     * <p>Nothing is resolved as a key name, so {@code C-c} types those three characters. Recorded
     * before dispatch, with a line break in the joined text treated as tmux treats it: a submit the
     * instant it reaches the pane, whether or not the caller also presses Enter afterward.
     */
    public void sendLiteral(List<String> keys) {
        sendLiteral(keys, () -> {});
    }

    /**
     * As {@link #sendLiteral(List)}, after a caller validates the prepared input destination.
     *
     * <p>The callback runs after recipient discovery and immediately before recording and dispatch.
     * If it throws, no keys are sent and no echo is recorded.
     *
     * @param beforeSend validates current input ownership
     */
    public void sendLiteral(List<String> keys, Runnable beforeSend) {
        Objects.requireNonNull(beforeSend, "beforeSend");
        List<String> argv = sendKeysArgv(keys, true);
        String joined = String.join("", keys);
        List<PaneId> recipients = keyRecipients();
        beforeSend.run();
        List<PaneEcho.Recorded> recorded = recipients.stream()
                .map(id -> server.echo().recordLiteral(identity(), id, joined))
                .toList();
        try {
            server.run(snapshot, argv);
        } catch (RuntimeException failure) {
            recorded.forEach(record -> settleFailure(record, failure));
            throw failure;
        }
        recorded.forEach(PaneEcho.Recorded::confirm);
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
        sendLiteral(List.of(command + "\r"));
    }

    private List<PaneId> keyRecipients() {
        if (!"1".equals(expand("#{pane_synchronized}"))) {
            return List.of(state.id());
        }
        RowFormat format = RowFormat.of(
                "pane_id",
                "pane_synchronized",
                "pane_in_mode",
                "pane_dead",
                "pane_input_off",
                "window_zoomed_flag",
                "pane_active");
        List<String> rows = server.run(
                        snapshot, List.of("list-panes", "-t", state.id().value(), "-F", format.template()))
                .stdout();
        return format.rows(rows).stream()
                .filter(row -> row.text("pane_id").equals(state.id().value())
                        || (row.flag("pane_synchronized")
                                && !row.flag("pane_in_mode")
                                && !row.flag("pane_dead")
                                && !row.flag("pane_input_off")
                                && (!row.flag("window_zoomed_flag") || row.flag("pane_active"))))
                .map(row -> new PaneId(row.text("pane_id")))
                .sorted(java.util.Comparator.comparing(PaneId::value))
                .toList();
    }

    private static void settleFailure(PaneEcho.Recorded recorded, RuntimeException failure) {
        if (failure instanceof TmuxTransportException transportFailure
                && transportFailure.outcome() != DispatchOutcome.NOT_DISPATCHED) {
            recorded.confirm();
        } else {
            recorded.rollback();
        }
    }

    /** Which server incarnation this pane's echo record belongs to. */
    private ServerIdentity identity() {
        return server.identity(snapshot);
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
                        List.of("display-message", "-p", "-t", state.id().value(), "--", format))
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
                new ArrayList<>(List.of("respawn-pane", "-k", "-t", state.id().value(), "--"));
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
        // Deliberately without the -- every other caller value here gets, for the reason
        // Server#runShell gives: tmux expands #(...) in this command, and on some releases the
        // terminator stops it. A shell command beginning with a dash is refused by tmux instead;
        // spell it ./-thing.
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
            wanted.ifPresent(name -> server.run(
                    snapshot, List.of("rename-window", "-t", fields.get(1), "--", TmuxFormats.literal(name))));
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
     * @throws ObjectDoesNotExistException if a command with no {@link SplitSpec.Builder#keepOnExit}
     *     exits before the pane it ran in can be read back — see {@link SplitSpec.Builder#running}.
     *     tmux still made the pane and ran the command; only this confirming read lost the race.
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
     * @throws ObjectDoesNotExistException if a command with no {@link SplitSpec.Builder#keepOnExit}
     *     exits before the pane it ran in can be read back — see {@link SplitSpec.Builder#running}.
     *     tmux still made the pane and ran the command; only this confirming read lost the race.
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
        // Recorded before dispatch: a wait already watching this pane must never see the pasted
        // content on screen before the record that discounts it exists. Rolled back below if the
        // paste never lands.
        PaneEcho.Recorded recorded = server.echo().recordLiteral(identity(), state.id(), text);
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
            settleFailure(recorded, failure);
            try {
                server.buffers().delete(buffer);
            } catch (RuntimeException ignored) {
                // Already gone, or the server is; neither changes what the caller is told.
            }
            throw failure;
        }
        recorded.confirm();
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
            PaneEcho.Recorded recorded = server.echo().recordLiteral(identity(), state.id(), text);
            try {
                server.run(
                        snapshot,
                        List.of(
                                "paste-buffer",
                                "-d",
                                "-b",
                                buffer,
                                "-t",
                                state.id().value()));
            } catch (RuntimeException failure) {
                settleFailure(recorded, failure);
                throw failure;
            }
            recorded.confirm();
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
        server.echo().forget(identity(), state.id());
    }

    /**
     * Takes a new capture and returns this pane as it is now.
     *
     * <p>This handle remains unchanged. Use the returned handle for subsequent state reads.
     *
     * @throws ObjectDoesNotExistException if the pane is gone from a server that still answers
     * @throws ServerNotRunningException if no daemon is running
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
