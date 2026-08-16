package io.github.libtmux;

import io.github.libtmux.batch.Batch;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.snapshot.ClientState;
import io.github.libtmux.snapshot.PaneState;
import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.snapshot.SessionState;
import io.github.libtmux.snapshot.WindowContext;
import io.github.libtmux.snapshot.WindowState;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ControlTransport;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import io.github.libtmux.transport.VirtualThreadTransport;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * One tmux server, reached over one transport.
 *
 * <p>Ownership is decided at construction and never inferred. {@link #open} creates a transport this
 * server closes exactly once; {@link #using} borrows one the caller keeps, so several servers can
 * share a transport and closing one leaves the others working.
 *
 * <p>Closing a server closes a client, not a tmux. It never kills the server process: sessions
 * outlive the program that made them, which is the entire point of tmux.
 */
public final class Server implements AutoCloseable {

    private static final RowFormat SESSIONS =
            RowFormat.of("session_id", "session_name", "session_attached", "session_windows");
    private static final RowFormat WINDOWS = RowFormat.of(
            "session_id",
            "window_id",
            "window_index",
            "window_name",
            "window_active",
            "window_panes",
            "window_linked",
            "window_width",
            "window_height",
            "window_layout");
    private static final String[] PANE_FIELDS = {
        "session_id",
        "window_id",
        "window_index",
        "pane_id",
        "pane_index",
        "pane_active",
        "pane_current_command",
        "pane_width",
        "pane_height",
        "pane_title",
        "pane_current_path",
        "pane_pid",
        "pane_at_top",
        "pane_at_bottom",
        "pane_at_left",
        "pane_at_right"
    };

    private static final RowFormat PANES = RowFormat.of(PANE_FIELDS);
    /** tmux gained pane_floating_flag in 3.7; before that the format expands to nothing. */
    private static final TmuxVersion FLOATING_SINCE = new TmuxVersion(3, 7, "");

    private static final RowFormat PANES_WITH_FLOATING = RowFormat.of(withFloating());

    private static String[] withFloating() {
        String[] fields = Arrays.copyOf(PANE_FIELDS, PANE_FIELDS.length + 1);
        fields[PANE_FIELDS.length] = "pane_floating_flag";
        return fields;
    }

    private static final RowFormat CLIENTS = RowFormat.of("client_name", "session_id");

    /** Long enough for a pending signal to come straight back, short enough not to be a wait. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofMillis(250);

    private final ServerConfig config;
    private final TmuxTransport transport;
    private final boolean owned;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Carriers made for a per-call override. Owned here, since here is what made them. */
    private final Map<ExecutionMode, TmuxTransport> overrides = new ConcurrentHashMap<>();

    private final ServerIdentity identity;
    private volatile @Nullable TmuxVersion version;

    private Server(ServerConfig config, TmuxTransport transport, boolean owned) {
        this.config = config;
        this.transport = transport;
        this.owned = owned;
        this.identity = ServerIdentity.of(transport.realm(), config.endpoint());
    }

    /**
     * Creates a detached session and returns that exact session.
     *
     * <p>tmux reports which session it made, so the result is exact even when another session
     * already carries the same name.
     *
     * <p>Keep {@code :} and {@code .} out of the name. tmux decides what to do with them and changes
     * its mind across the supported range: 3.2a through 3.6 rewrite each one to {@code _}, 3.7
     * refuses the name, and 3.7a onwards keeps it — where it then cannot address the session,
     * because a target splits on both. {@link Session#name()} reports what tmux settled on.
     */
    public Session newSession(String name) {
        return newSession(SessionSpec.builder().named(name).build());
    }

    /**
     * Creates a session as described.
     *
     * <pre>{@code
     * Session build = server.newSession(s -> s.named("build").sized(new Dimensions(120, 40)));
     * }</pre>
     *
     * @param configure receives a builder holding tmux's defaults
     * @throws UnsupportedTmuxVersion if the spec asks for something this server does not have
     */
    public Session newSession(Consumer<SessionSpec.Builder> configure) {
        SessionSpec.Builder builder = SessionSpec.builder();
        configure.accept(builder);
        return newSession(builder.build());
    }

    /**
     * Creates a session according to a spec, which may be reused across servers.
     *
     * @throws UnsupportedTmuxVersion if the spec asks for something this server does not have
     */
    public Session newSession(SessionSpec spec) {
        List<String> reported = run(spec.argv("#{session_id}", this::version)).stdout();
        SessionId created = new SessionId(reported.get(0));
        ServerSnapshot fresh = snapshot();
        return fresh.session(created)
                .map(session -> new Session(this, fresh, session))
                .orElseThrow(() -> new ObjectDoesNotExist("the session just created is already gone"));
    }

    /** Whether a session with this name exists. */
    public boolean hasSession(String name) {
        return cmd("has-session", "-t", "=" + name).succeeded();
    }

    /**
     * Ends the session with this name, and everything in it.
     *
     * <p>The name is matched exactly. tmux would otherwise take a prefix, so asking to kill
     * {@code build} could end {@code build-cache} instead.
     *
     * @throws LibTmuxException if no session carries the name
     */
    public void killSession(String name) {
        Objects.requireNonNull(name, "name");
        run(List.of("kill-session", "-t", "=" + name));
    }

    /**
     * Whether the server is running and answering.
     *
     * <p>The explicit primitive behind the lenient list accessors: those return an empty list for
     * both "no sessions" and "no server", and this is how a caller tells the two apart.
     */
    public boolean isAlive() {
        return cmd("display-message", "-p", "#{pid}").succeeded();
    }

    /**
     * Checks the server is answering, and says so loudly when it is not.
     *
     * @throws LibTmuxException if the server is not running or not answering
     */
    public void raiseIfDead() {
        if (!isAlive()) {
            throw new LibTmuxException("no tmux server is answering on this endpoint");
        }
    }

    /**
     * Ends the tmux server and every session on it.
     *
     * <p>Deliberately explicit and deliberately not what {@link #close()} does: closing a client is
     * not a reason to end everyone else's sessions.
     *
     * <p>Killing a server that has already gone is not a failure. Which is harder to detect than it
     * sounds: tmux answers a doomed request with {@code no server running} most of the time, but
     * with {@code server exited unexpectedly} when it reaches a socket whose server is still
     * exiting — on every release from 3.3a onwards, and on one in five attempts on 3.7. So the
     * postcondition is checked rather than the wording, which has changed before and says nothing
     * a second look cannot answer better.
     */
    public void killServer() {
        CommandResult result = cmd("kill-server");
        if (result.succeeded() || !isAlive()) {
            return;
        }
        throw new LibTmuxException("could not kill the server: " + String.join("; ", result.stderr()));
    }

    /**
     * Collects several commands to run in one tmux invocation.
     *
     * <p>Each operation gets its own outcome. tmux discards a group after the first failure, so a
     * single exit status cannot say which command failed or which never ran.
     */
    public Batch batch() {
        return new Batch(argv -> cmd(argv, config.defaultTimeout()));
    }

    /**
     * Starts a chain of commands where each one acts on what the last one made.
     *
     * <p>tmux moves its own current target as a group runs, so a chain needs no round trip to learn
     * the id of a window or pane it just created.
     */
    public CommandChain chain() {
        return new CommandChain(batch());
    }

    /** 3.2a answers "unknown command" for both of the prompt-history commands. */
    private static final TmuxVersion PROMPT_HISTORY_SINCE = new TmuxVersion(3, 3, "a");

    /** tmux lost run-shell's output in 3.3a and found it again in 3.5. */
    private static final TmuxVersion SHELL_OUTPUT_LOST = new TmuxVersion(3, 3, "");

    private static final TmuxVersion SHELL_OUTPUT_FOUND = new TmuxVersion(3, 5, "");

    /**
     * Expands a tmux format against the server, and answers with what it came to.
     *
     * <p>The server-wide counterpart to {@link Pane#expand}: fields such as {@code #{pid}} and
     * {@code #{version}} that belong to no session in particular.
     *
     * @param format a tmux format, usually of the shape {@code #{name}}
     * @return the expansion, empty when the format expanded to nothing
     */
    public String expand(String format) {
        Objects.requireNonNull(format, "format");
        List<String> reported = run(List.of("display-message", "-p", format)).stdout();
        return reported.isEmpty() ? "" : reported.get(0);
    }

    /**
     * Runs a shell command through tmux, for its effect.
     *
     * <p>Nothing is claimed about what it printed — see {@link #runShellCapturing} for that, and for
     * why the two are separate.
     *
     * @param command run by the user's shell, so it may redirect and pipe
     */
    public void runShell(String command) {
        Objects.requireNonNull(command, "command");
        run(List.of("run-shell", command));
    }

    /**
     * Runs a shell command through tmux and answers with what it printed.
     *
     * <p>Separate from {@link #runShell} because tmux 3.3a and 3.4 run the command and then report
     * nothing, on every attempt. A caller who wants the effect is fine there; a caller who wants the
     * output would silently get none, so this one refuses rather than answering emptily.
     *
     * @throws UnsupportedTmuxVersion on the releases that lose the output
     */
    public List<String> runShellCapturing(String command) {
        Objects.requireNonNull(command, "command");
        TmuxVersion running = version();
        if (running.atLeast(SHELL_OUTPUT_LOST) && !running.atLeast(SHELL_OUTPUT_FOUND)) {
            throw new UnsupportedTmuxVersion(
                    "reading what run-shell printed is broken between tmux 3.3a and 3.4, and this server runs "
                            + running);
        }
        return run(List.of("run-shell", command)).stdout();
    }

    /** Every command this tmux knows, as it prints them. */
    public List<String> listCommands() {
        return run(List.of("list-commands")).stdout();
    }

    /**
     * Runs one tmux command or another, according to whether a shell command succeeds.
     *
     * <p>The choosing happens inside tmux rather than here, which is the point: the condition and
     * both outcomes go out as one request, so nothing can change between asking and acting.
     *
     * @param condition a shell command, judged by its exit status
     * @param whenTrue the tmux command to run when the condition succeeds
     */
    public void ifShell(String condition, String whenTrue) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(whenTrue, "whenTrue");
        run(List.of("if-shell", condition, whenTrue));
    }

    /**
     * Runs one tmux command or another, according to whether a shell command succeeds.
     *
     * @param condition a shell command, judged by its exit status
     * @param whenTrue the tmux command to run when the condition succeeds
     * @param whenFalse the tmux command to run when it does not
     */
    public void ifShell(String condition, String whenTrue, String whenFalse) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(whenTrue, "whenTrue");
        Objects.requireNonNull(whenFalse, "whenFalse");
        run(List.of("if-shell", condition, whenTrue, whenFalse));
    }

    /** Locks every client attached to this server. */
    public void lock() {
        run(List.of("lock-server"));
    }

    /**
     * The server's own message log, newest last.
     *
     * <p>Deliberately not version-gated, though tmux before 3.6 answers {@code no current client}
     * when nothing is attached. A gate would refuse the case that works: with a client attached the
     * log is readable on every supported release, and only a detached 3.2a through 3.5 cannot answer.
     * The failure tmux reports is accurate and says exactly what is missing, so it is left to reach
     * the caller.
     *
     * @throws LibTmuxException before 3.6 when no client is attached
     */
    public List<String> messages() {
        return run(List.of("show-messages")).stdout();
    }

    /**
     * What has been typed at tmux's command prompt, oldest first.
     *
     * @throws UnsupportedTmuxVersion before 3.3a, which has no such command at all
     */
    public List<String> promptHistory() {
        requirePromptHistory();
        return run(List.of("show-prompt-history")).stdout();
    }

    /**
     * Forgets what has been typed at tmux's command prompt.
     *
     * @throws UnsupportedTmuxVersion before 3.3a, which has no such command at all
     */
    public void clearPromptHistory() {
        requirePromptHistory();
        run(List.of("clear-prompt-history"));
    }

    private void requirePromptHistory() {
        TmuxVersion running = version();
        if (!running.atLeast(PROMPT_HISTORY_SINCE)) {
            throw new UnsupportedTmuxVersion("the command prompt's history", PROMPT_HISTORY_SINCE, running);
        }
    }

    /** Binds a key to a tmux command. */
    public void bindKey(String key, List<String> command) {
        List<String> argv = new ArrayList<>(List.of("bind-key", key));
        argv.addAll(command);
        run(argv);
    }

    /** Removes a key binding. */
    public void unbindKey(String key) {
        run(List.of("unbind-key", key));
    }

    /** Every key binding, as tmux prints them. */
    public List<String> listKeys() {
        CommandResult result = cmd("list-keys");
        return result.succeeded() ? result.stdout() : List.of();
    }

    /**
     * Waits for something to signal a channel.
     *
     * <p>tmux's own {@code wait-for} has two traps, and this exists to close both.
     *
     * <p>It exits successfully when the server dies under the waiter, which is indistinguishable
     * from a real signal, so the server is checked afterwards rather than believed.
     *
     * <p>A signal sent when nobody is waiting is remembered, and satisfies the next wait whenever
     * that happens — possibly in a later run of a different program. A channel carrying a stale
     * signal therefore wakes a waiter that nothing actually signalled. Use {@link #drain} first when
     * the channel's history is not yours.
     *
     * @param channel the channel name, which is shared by everything on this server
     * @param timeout how long to wait
     * @return why the wait ended, which is never simply "successfully"
     */
    public WakeReason waitFor(String channel, Duration timeout) {
        try {
            cmd(List.of("wait-for", channel), timeout);
        } catch (io.github.libtmux.transport.TmuxTransportException e) {
            // The transport killed the waiting client at the deadline; nothing signalled it.
            return isAlive() ? WakeReason.TIMED_OUT : WakeReason.SERVER_GONE;
        }
        return isAlive() ? WakeReason.SIGNALLED : WakeReason.SERVER_GONE;
    }

    /** Signals a channel, waking one waiter, or being remembered until something waits. */
    public void signal(String channel) {
        run(List.of("wait-for", "-S", channel));
    }

    /**
     * Consumes a signal already waiting on a channel, so a stale one cannot satisfy a later wait.
     *
     * @return whether a signal was there to consume
     */
    public boolean drain(String channel) {
        return waitFor(channel, DRAIN_TIMEOUT) == WakeReason.SIGNALLED;
    }

    /** The server's paste buffers, which every session shares. */
    public Buffers buffers() {
        return new Buffers(this);
    }

    /**
     * Runs a file of tmux commands, as a configuration file would be run.
     *
     * @throws LibTmuxException if tmux could not read or run it
     */
    public void sourceFile(Path file) {
        run(List.of("source-file", file.toString()));
    }

    /** The server-wide options, the ones tmux keeps once per server. */
    public Options options() {
        return Options.server(this);
    }

    /** The global session options every session inherits unless it sets its own. */
    public Options globalOptions() {
        return Options.global(this);
    }

    /** The global hooks every session inherits. */
    public Hooks hooks() {
        return Hooks.global(this);
    }

    /**
     * Which tmux this server is running.
     *
     * <p>Asked of the running server rather than of the binary, because the server may have been
     * started by a different build than the one this client is invoking.
     */
    public TmuxVersion version() {
        TmuxVersion known = version;
        if (known == null) {
            // One tmux build serves a server for its whole life, so this is asked once.
            known = TmuxVersion.parse(
                    run(List.of("display-message", "-p", "#{version}")).stdout().get(0));
            version = known;
        }
        return known;
    }

    /** Which server this is. Every handle taken from it is scoped by this. */
    public ServerIdentity identity() {
        return identity;
    }

    /** A server over a transport it owns and closes. */
    public static Server open(ServerConfig config) {
        Objects.requireNonNull(config, "config");
        return new Server(config, carrierFor(config), true);
    }

    /**
     * Builds the carrier the config asked for.
     *
     * <p>A mode is a transport choice and nothing more, which is why this is the only place the
     * enum is consulted. See {@code docs/spikes/19} for why nothing above here has to care.
     */
    private static TmuxTransport carrierFor(ServerConfig config) {
        return switch (config.mode()) {
            case DIRECT -> new ProcessTransport();
            case CONTROL -> new ControlTransport(config, new ProcessTransport());
            case VIRTUAL -> new VirtualThreadTransport(new ProcessTransport());
        };
    }

    /** A server over a transport the caller owns. Closing this server never closes it. */
    public static Server using(ServerConfig config, TmuxTransport transport) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(transport, "transport");
        return new Server(config, transport, false);
    }

    /** A builder holding the documented defaults. */
    public static Builder builder() {
        return new Builder(ServerConfig.builder(), null);
    }

    /** How this server was configured. */
    public ServerConfig config() {
        return config;
    }

    /**
     * Runs one tmux command against this server.
     *
     * @param argv the tmux command and its arguments, each already a separate element
     * @return the result, in which a nonzero exit is data rather than a failure
     */
    public CommandResult cmd(String... argv) {
        return cmd(List.of(argv), config.defaultTimeout());
    }

    /** Runs one tmux command against this server. */
    public CommandResult cmd(List<String> argv) {
        return cmd(argv, config.defaultTimeout());
    }

    /**
     * Runs one tmux command against this server, carried the way this call asks rather than the way
     * the config asks.
     *
     * <p>Precedence, highest first:
     *
     * <ol>
     *   <li>this argument
     *   <li>{@link ServerConfig.Builder#mode}
     *   <li>{@link ExecutionMode#DIRECT}
     * </ol>
     *
     * <p>Rarely worth reaching for. Nothing a handle returns depends on which carrier answered — see
     * {@code docs/spikes/19} — so this changes cost and nothing else, and the one case where the
     * carrier affects correctness routes itself. It exists for the caller who has measured a reason.
     *
     * <p>A carrier created for an override belongs to this server and is closed with it.
     */
    public CommandResult cmd(List<String> argv, Duration timeout, ExecutionMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (closed.get()) {
            throw new IllegalStateException("server is closed");
        }
        return carrierFor(mode).execute(new CommandRequest(config.endpointCommand(), argv, timeout));
    }

    /**
     * The carrier for one mode, made once and kept.
     *
     * <p>The configured mode reuses the transport this server was built with, owned or borrowed as
     * it always was. Any other mode gets a carrier of its own, which this server owns however the
     * first one was obtained: it made it, so it closes it.
     */
    private TmuxTransport carrierFor(ExecutionMode mode) {
        if (mode == config.mode()) {
            return transport;
        }
        return overrides.computeIfAbsent(mode, wanted -> switch (wanted) {
            case DIRECT -> new ProcessTransport();
            case CONTROL -> new ControlTransport(config, new ProcessTransport());
            case VIRTUAL -> new VirtualThreadTransport(new ProcessTransport());
        });
    }

    /** Runs one tmux command against this server, overriding the configured deadline. */
    public CommandResult cmd(List<String> argv, Duration timeout) {
        if (closed.get()) {
            throw new IllegalStateException("server is closed");
        }
        List<String> endpoint = config.endpointCommand();
        List<String> command = new ArrayList<>(endpoint.size());
        command.addAll(endpoint);
        return transport.execute(new CommandRequest(command, argv, timeout));
    }

    /**
     * Captures the whole hierarchy, in four listings whatever its size.
     *
     * <p>One server-wide listing per kind of object, so ordering and membership stay tmux's decision
     * rather than being re-derived from another listing's rows.
     *
     * <p>Strict, unlike the lenient list accessors: a capture that failed raises instead of
     * returning an apparently valid empty graph, because a caller cannot tell those apart.
     *
     * @throws LibTmuxException if any listing failed
     */
    public ServerSnapshot snapshot() {
        List<SessionState> sessions = new ArrayList<>();
        for (List<String> row : rows(SESSIONS, "list-sessions")) {
            sessions.add(new SessionState(
                    new SessionId(row.get(0)), row.get(1), "1".equals(row.get(2)), Integer.parseInt(row.get(3))));
        }
        List<WindowState> windows = new ArrayList<>();
        for (List<String> row : rows(WINDOWS, "list-windows", "-a")) {
            windows.add(new WindowState(
                    context(row.get(0), row.get(2), row.get(1)),
                    row.get(3),
                    "1".equals(row.get(4)),
                    Integer.parseInt(row.get(5)),
                    "1".equals(row.get(6)),
                    new Dimensions(Integer.parseInt(row.get(7)), Integer.parseInt(row.get(8))),
                    row.get(9)));
        }
        boolean floatingKnown = version().atLeast(FLOATING_SINCE);
        RowFormat paneFormat = floatingKnown ? PANES_WITH_FLOATING : PANES;
        List<PaneState> panes = new ArrayList<>();
        for (List<String> row : rows(paneFormat, "list-panes", "-a")) {
            panes.add(new PaneState(
                    context(row.get(0), row.get(2), row.get(1)),
                    new PaneId(row.get(3)),
                    Integer.parseInt(row.get(4)),
                    "1".equals(row.get(5)),
                    row.get(6),
                    new Dimensions(Integer.parseInt(row.get(7)), Integer.parseInt(row.get(8))),
                    row.get(9),
                    Path.of(row.get(10)),
                    Long.parseLong(row.get(11)),
                    new PaneEdges(
                            "1".equals(row.get(12)),
                            "1".equals(row.get(13)),
                            "1".equals(row.get(14)),
                            "1".equals(row.get(15))),
                    floatingKnown ? Optional.of("1".equals(row.get(16))) : Optional.empty()));
        }
        List<ClientState> clients = new ArrayList<>();
        for (List<String> row : rows(CLIENTS, "list-clients")) {
            clients.add(new ClientState(
                    row.get(0), row.get(1).isEmpty() ? Optional.empty() : Optional.of(new SessionId(row.get(1)))));
        }
        return ServerSnapshot.of(Instant.now(), sessions, windows, panes, clients);
    }

    /**
     * Every session, captured now.
     *
     * <p>Lenient, by the libtmux contract these accessors have always had: a tmux failure produces
     * an empty list rather than raising, because "no sessions" is the ordinary answer and callers
     * mostly cannot act on the difference. Those that can use {@link #snapshot()}, which is strict.
     */
    public List<Session> sessions() {
        ServerSnapshot captured = lenient();
        return captured.sessions().stream()
                .map(session -> new Session(this, captured, session))
                .toList();
    }

    /** Every winlink on the server, captured now, including a linked window once per session. */
    public List<Window> windows() {
        ServerSnapshot captured = lenient();
        return captured.windows().stream()
                .map(window -> new Window(this, captured, window))
                .toList();
    }

    /** Every pane on the server, captured now. */
    public List<Pane> panes() {
        ServerSnapshot captured = lenient();
        return captured.panes().stream()
                .map(pane -> new Pane(this, captured, pane))
                .toList();
    }

    /** Every attached client, captured now. */
    public List<Client> clients() {
        ServerSnapshot captured = lenient();
        return captured.clients().stream()
                .map(client -> new Client(this, captured, client))
                .toList();
    }

    /** Every session a client is attached to, captured now. */
    public List<Session> attachedSessions() {
        return sessions().stream().filter(Session::attached).toList();
    }

    /**
     * Runs a command that is expected to work, and raises when it did not.
     *
     * <p>The counterpart to {@link #cmd}: use this when a nonzero exit means the thing you asked for
     * did not happen, and {@code cmd} when a nonzero exit is an answer you want to inspect. tmux
     * reports an ordinary miss and a real problem the same way, so only the caller knows which it is.
     *
     * @throws LibTmuxException if tmux reported a nonzero exit
     */
    public CommandResult run(List<String> argv) {
        CommandResult result = cmd(argv);
        if (!result.succeeded()) {
            throw new LibTmuxException("tmux " + argv.get(0) + " failed: " + String.join("; ", result.stderr()));
        }
        return result;
    }

    private ServerSnapshot lenient() {
        try {
            return snapshot();
        } catch (LibTmuxException e) {
            return ServerSnapshot.of(Instant.now(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private static WindowContext context(String session, String index, String window) {
        return new WindowContext(
                new SessionId(session), new WindowIndex(Integer.parseInt(index)), new WindowId(window));
    }

    /**
     * Runs one listing and splits its rows.
     *
     * <p>An empty server is not a failure: {@code list-sessions} reports "no server running" as a
     * nonzero exit, and a capture of nothing is still a capture.
     */
    private List<List<String>> rows(RowFormat format, String... command) {
        List<String> argv = new ArrayList<>(command.length + 2);
        argv.addAll(List.of(command));
        argv.add("-F");
        argv.add(format.template());
        CommandResult result = cmd(argv);
        if (!result.succeeded()) {
            if (result.stderr().stream().anyMatch(line -> line.contains("no server running"))) {
                return List.of();
            }
            throw new LibTmuxException("tmux " + command[0] + " failed: " + String.join("; ", result.stderr()));
        }
        return result.stdout().stream().map(format::split).toList();
    }

    /** A builder holding every configuration and ownership choice this server made. */
    public Builder toBuilder() {
        // An owned transport is not shared: this server will close it, so a derived server gets its own.
        return new Builder(config.toBuilder(), owned ? null : transport);
    }

    /** Releases an owned transport. Idempotent, and never kills tmux. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // A carrier made for an override is this server's however the first one was obtained, so it
        // is closed either way. Borrowing a transport says nothing about the ones made afterwards.
        overrides.values().forEach(TmuxTransport::close);
        overrides.clear();
        if (owned) {
            transport.close();
        }
    }

    /** Collects configuration and the ownership choice, then builds a server. */
    public static final class Builder {

        private final ServerConfig.Builder config;
        private @Nullable TmuxTransport transport;

        private Builder(ServerConfig.Builder config, @Nullable TmuxTransport transport) {
            this.config = config;
            this.transport = transport;
        }

        /** Sets the tmux executable. */
        public Builder binary(String binary) {
            config.binary(binary);
            return this;
        }

        /** Sets which server to talk to. */
        public Builder endpoint(ServerEndpoint endpoint) {
            config.endpoint(endpoint);
            return this;
        }

        /** Pins the config file tmux reads. */
        public Builder configFile(Path configFile) {
            config.configFile(configFile);
            return this;
        }

        /** Sets the deadline a request gets when the caller does not supply one. */
        public Builder defaultTimeout(Duration defaultTimeout) {
            config.defaultTimeout(defaultTimeout);
            return this;
        }

        /** Borrows a caller-owned transport, which the built server will never close. */
        public Builder transport(TmuxTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            return this;
        }

        /** Builds the server, owning a new transport unless one was borrowed. */
        public Server build() {
            ServerConfig built = config.build();
            return transport == null ? open(built) : using(built, transport);
        }
    }
}
