package io.github.libtmux;

import io.github.libtmux.batch.Batch;
import io.github.libtmux.internal.CommandStrings;
import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.snapshot.WindowContext;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final ServerConfig config;
    private final TmuxTransport transport;
    private final boolean owned;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final ServerIdentity identity;
    private final SnapshotCapture capture;

    private Server(ServerConfig config, TmuxTransport transport, boolean owned) {
        this.config = config;
        this.transport = transport;
        this.owned = owned;
        this.identity = ServerIdentity.of(transport.realm(), config.endpoint());
        this.capture = new SnapshotCapture(this);
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
     * @throws UnsupportedTmuxVersionException if the spec asks for something this server does not have
     */
    public Session newSession(Consumer<SessionSpec.Builder> configure) {
        SessionSpec.Builder builder = SessionSpec.builder();
        configure.accept(builder);
        return newSession(builder.build());
    }

    /**
     * Creates a session according to a spec, which may be reused across servers.
     *
     * @throws UnsupportedTmuxVersionException if the spec asks for something this server does not have
     */
    public Session newSession(SessionSpec spec) {
        List<String> reported = run(spec.argv("#{session_id}", this::version)).stdout();
        SessionId created = new SessionId(reported.get(0));
        ServerSnapshot fresh = snapshot();
        return fresh.session(created)
                .map(session -> new Session(this, fresh, session))
                .orElseThrow(() -> new ObjectDoesNotExistException("the session just created is already gone"));
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
     * <p>Returns false when tmux refuses the probe. Transport failures still throw.
     */
    public boolean isAlive() {
        return isAlive(config.defaultTimeout());
    }

    /**
     * Whether the server is running and answering, within a deadline of the caller's choosing.
     *
     * <p>The deadline is per call rather than per handle, because a probe that has to finish — a
     * test fixture confirming its server is gone, a shutdown path that cannot hang — is not bound
     * by the same budget as ordinary work through the same {@code Server}.
     *
     * @param timeout how long to wait for an answer before treating the server as unreachable
     */
    public boolean isAlive(Duration timeout) {
        return cmd(List.of("display-message", "-p", "#{pid}"), timeout).succeeded();
    }

    /**
     * Requires a running tmux daemon that answers the liveness probe.
     *
     * @throws LibTmuxException if the server is not running or not answering
     */
    public void requireAlive() {
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
        killServer(config.defaultTimeout());
    }

    /**
     * Ends the tmux server and every session on it, within a deadline of the caller's choosing.
     *
     * <p>Bounds both halves — the kill and the second look that confirms it — because a teardown
     * that hangs is worse than one that reports it could not finish.
     *
     * @param timeout how long to allow for each of the two commands
     */
    public void killServer(Duration timeout) {
        CommandResult result = cmd(List.of("kill-server"), timeout);
        if (result.succeeded() || !isAlive(timeout)) {
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
        return new Batch(commands -> transport.execute(request(commands, config.defaultTimeout(), "")));
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
     * @return the expansion, whole when it spans lines and empty when the format expanded to
     *     nothing
     */
    public String expand(String format) {
        Objects.requireNonNull(format, "format");
        List<String> reported = run(List.of("display-message", "-p", format)).stdout();
        return String.join("\n", reported);
    }

    /**
     * Runs a shell command through tmux, for its effect.
     *
     * <p>Nothing is claimed about what it printed — see {@link #runShellCapturing} for that, and for
     * why the two are separate.
     *
     * @param command run by the user's shell, so it may redirect and pipe
     *
     * <p>tmux expands {@code #(...)} in this command before a shell sees it, and shell quoting does
     * not prevent that. Pass any interpolated value through {@link TmuxFormats#literal} unless you
     * mean it to be expanded.
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
     * @throws UnsupportedTmuxVersionException on the releases that lose the output
     *
     * <p>tmux expands {@code #(...)} in this command before a shell sees it, and shell quoting does
     * not prevent that. Pass any interpolated value through {@link TmuxFormats#literal} unless you
     * mean it to be expanded.
     */
    public List<String> runShellCapturing(String command) {
        Objects.requireNonNull(command, "command");
        TmuxVersion running = version();
        if (running.atLeast(SHELL_OUTPUT_LOST) && !running.atLeast(SHELL_OUTPUT_FOUND)) {
            throw new UnsupportedTmuxVersionException(
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
     *
     * <p>tmux expands {@code #(...)} in the condition before a shell sees it, and shell quoting does
     * not prevent that. Pass any interpolated value through {@link TmuxFormats#literal} unless you
     * mean it to be expanded.
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
     * @throws UnsupportedTmuxVersionException before 3.3a, which has no such command at all
     */
    public List<String> promptHistory() {
        requirePromptHistory();
        return run(List.of("show-prompt-history")).stdout();
    }

    /**
     * Forgets what has been typed at tmux's command prompt.
     *
     * @throws UnsupportedTmuxVersionException before 3.3a, which has no such command at all
     */
    public void clearPromptHistory() {
        requirePromptHistory();
        run(List.of("clear-prompt-history"));
    }

    private void requirePromptHistory() {
        TmuxVersion running = version();
        if (!running.atLeast(PROMPT_HISTORY_SINCE)) {
            throw new UnsupportedTmuxVersionException("the command prompt's history", PROMPT_HISTORY_SINCE, running);
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

    /** One of this server's wait-for channels, which is where a signal is sent and waited for. */
    public Channel channel(String name) {
        return new Channel(this, name);
    }

    /** Reads only validated tmux variable names, never caller-authored format syntax. */
    public Map<String, String> variables(List<String> names) {
        return variables(names, this::expand);
    }

    Map<String, String> variables(List<String> names, Function<String, String> expand) {
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(expand, "expand");
        if (names.isEmpty()) {
            throw new IllegalArgumentException("variable names are empty");
        }
        if (names.size() > 32) {
            throw new IllegalArgumentException("at most 32 tmux variables may be read at once");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : names) {
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "invalid tmux variable '" + name + "'; expected [A-Za-z][A-Za-z0-9_]*");
            }
            values.put(name, expand.apply("#{" + name + "}"));
        }
        return Collections.unmodifiableMap(values);
    }

    /** Enables or disables mouse handling for sessions on this server. */
    public void setMouseEnabled(boolean enabled) {
        globalOptions().set("mouse", enabled ? "on" : "off");
    }

    WakeReason awaitChannel(String channel, Duration timeout, boolean reserveSignalCapacity) {
        try {
            CommandRequest request = request(List.of("wait-for", channel), timeout);
            if (reserveSignalCapacity) {
                transport.executeWaiting(request);
            } else {
                transport.execute(request);
            }
        } catch (io.github.libtmux.transport.TmuxTimeoutException e) {
            if (reserveSignalCapacity && e.outcome() == DispatchOutcome.NOT_DISPATCHED) {
                throw e;
            }
            // The transport killed the waiting client at the deadline; nothing signalled it.
            return isAlive() ? WakeReason.TIMED_OUT : WakeReason.SERVER_GONE;
        }
        return isAlive() ? WakeReason.SIGNALLED : WakeReason.SERVER_GONE;
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
        return capture.process()
                .map(SnapshotCapture.ServerProcess::version)
                .orElseThrow(() -> new LibTmuxException("no tmux server is answering on this endpoint"));
    }

    TmuxVersion version(ServerSnapshot snapshot) {
        return snapshot.serverVersion().orElseGet(this::version);
    }

    /** Which server this is. Every handle taken from it is scoped by this. */
    public ServerIdentity identity() {
        return identity;
    }

    void requireSameServer(Server other) {
        Objects.requireNonNull(other, "other");
        if (!identity.equals(other.identity)) {
            throw new IllegalArgumentException("handles belong to different tmux servers");
        }
    }

    void requireSameIncarnation(ServerSnapshot snapshot, Server other, ServerSnapshot otherSnapshot) {
        requireSameServer(other);
        if (!identity(snapshot).equals(other.identity(otherSnapshot))) {
            throw new IllegalArgumentException("handles belong to different tmux server incarnations");
        }
    }

    ServerIdentity identity(ServerSnapshot snapshot) {
        return snapshot.serverPid().isPresent()
                ? identity.at(snapshot.serverPid().orElseThrow())
                : identity;
    }

    /** A server over a transport it owns and closes. */
    public static Server open(ServerConfig config) {
        Objects.requireNonNull(config, "config");
        return new Server(config, new ProcessTransport(), true);
    }

    /** A server over a transport the caller owns. Closing this server never closes it. */
    public static Server using(ServerConfig config, TmuxTransport transport) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(transport, "transport");
        return new Server(config, transport, false);
    }

    /**
     * This server, with every command bounded by a deadline of the caller's choosing.
     *
     * <p>Shares this server's transport, so it has the same identity and every handle taken through
     * it is interchangeable with this server's; closing it releases nothing. {@link #toBuilder} is not
     * how to spell this: a server that owns its transport hands a derived server a transport of its
     * own, which a wait polling every fifty milliseconds would multiply.
     */
    Server within(Duration timeout) {
        return new Server(config.toBuilder().defaultTimeout(timeout).build(), transport, false);
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

    /** Runs one tmux command against this server, overriding the configured deadline. */
    public CommandResult cmd(List<String> argv, Duration timeout) {
        return cmd(argv, timeout, "");
    }

    private CommandResult cmd(List<String> argv, Duration timeout, String input) {
        return transport.execute(request(List.of(argv), timeout, input));
    }

    private CommandRequest request(List<String> argv, Duration timeout) {
        return request(List.of(argv), timeout, "");
    }

    private CommandRequest request(List<List<String>> commands, Duration timeout, String input) {
        requireOpen();
        return new CommandRequest(config.endpointCommand(), commands, timeout, input);
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("server is closed");
        }
    }

    /**
     * Captures the whole hierarchy in at most four listings, retrying once if the server is
     * replaced.
     *
     * <p>One server-wide listing per kind of object, so ordering and membership stay tmux's decision
     * rather than being re-derived from another listing's rows.
     *
     * <p>A failed read throws, including when no daemon is running. An empty graph means a live
     * server successfully reported no sessions.
     *
     * @throws LibTmuxException if a listing fails or the listings cannot form one valid snapshot
     */
    public ServerSnapshot snapshot() {
        requireOpen();
        try {
            return capture.attempt()
                    .or(capture::attempt)
                    .orElseThrow(() -> new LibTmuxException("tmux server changed during snapshot capture"));
        } catch (LibTmuxException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LibTmuxException("could not hydrate tmux snapshot: " + e.getMessage(), e);
        }
    }

    /**
     * Every session, captured now.
     *
     * <p>Returns an immutable list in tmux order. Empty means a live server reported no sessions.
     *
     * @throws LibTmuxException if capture fails, including when no daemon is running
     */
    public List<Session> sessions() {
        ServerSnapshot captured = snapshot();
        return captured.sessions().stream()
                .map(session -> new Session(this, captured, session))
                .toList();
    }

    /**
     * Captures every winlink, preserving each session and index placement.
     *
     * @return an immutable list in tmux order
     * @throws LibTmuxException if capture fails, including when no daemon is running
     */
    public List<Window> windows() {
        ServerSnapshot captured = snapshot();
        return captured.windows().stream()
                .map(window -> new Window(this, captured, window))
                .toList();
    }

    /**
     * Captures every pane on the server.
     *
     * @return an immutable list in tmux order
     * @throws LibTmuxException if capture fails, including when no daemon is running
     */
    public List<Pane> panes() {
        ServerSnapshot captured = snapshot();
        return captured.panes().stream()
                .map(pane -> new Pane(this, captured, pane))
                .toList();
    }

    /**
     * The session with this name, captured now.
     *
     * <p>One read, so the handle carries a capture the way {@link #sessions()} does. The name is
     * matched exactly; tmux would otherwise take a prefix, so asking for {@code build} could answer
     * with {@code build-cache}.
     *
     * <p>Empty means a successful capture did not contain that name. Capture failures throw.
     *
     * @throws LibTmuxException if capture fails, including when no daemon is running
     */
    public Optional<Session> session(String name) {
        Objects.requireNonNull(name, "name");
        ServerSnapshot captured = snapshot();
        return captured.session(name).map(session -> new Session(this, captured, session));
    }

    /**
     * The session with this id, captured now.
     *
     * @return empty only when a successful capture contains no match
     * @throws LibTmuxException if capture fails, including when no daemon is running
     */
    public Optional<Session> session(SessionId id) {
        Objects.requireNonNull(id, "id");
        ServerSnapshot captured = snapshot();
        return captured.session(id).map(session -> new Session(this, captured, session));
    }

    /**
     * The pane with this id, captured now.
     *
     * @return empty only when a successful capture contains no match
     * @throws LibTmuxException if capture fails, including when no daemon is running
     */
    public Optional<Pane> pane(PaneId id) {
        Objects.requireNonNull(id, "id");
        ServerSnapshot captured = snapshot();
        return captured.panes().stream()
                .filter(pane -> pane.id().equals(id))
                .findFirst()
                .map(pane -> new Pane(this, captured, pane));
    }

    /**
     * The winlink at this exact position, captured now.
     *
     * @return empty only when a successful capture contains no match
     * @throws LibTmuxException if capture fails, including when no daemon is running
     */
    public Optional<Window> window(WindowContext context) {
        Objects.requireNonNull(context, "context");
        ServerSnapshot captured = snapshot();
        return captured.window(context).map(window -> new Window(this, captured, window));
    }

    /**
     * Every winlink of the window with this id, captured now.
     *
     * <p>A list, not an {@link Optional}, and that is the whole point. One window can be linked into
     * several sessions, and each link is a separate handle with its own index and its own active
     * flag. A finder that answered with the first would quietly act on whichever link tmux happened
     * to list first — so this hands back all of them and lets the caller say which it meant, or use
     * {@link #window(WindowContext)} to name one exactly.
     *
     * @return an immutable list in tmux order, empty if the window was not found
     * @throws LibTmuxException if capture fails, including when no daemon is running
     */
    public List<Window> windows(WindowId id) {
        Objects.requireNonNull(id, "id");
        ServerSnapshot captured = snapshot();
        return captured.windows().stream()
                .filter(window -> window.context().window().equals(id))
                .map(window -> new Window(this, captured, window))
                .toList();
    }

    /**
     * Captures every attached client.
     *
     * @return an immutable list in tmux order
     * @throws LibTmuxException if capture fails, including when no daemon is running
     */
    public List<Client> clients() {
        ServerSnapshot captured = snapshot();
        return captured.clients().stream()
                .map(client -> new Client(this, captured, client))
                .toList();
    }

    /**
     * Captures every session a client is attached to.
     *
     * @return an immutable list in tmux order
     * @throws LibTmuxException if capture fails, including when no daemon is running
     */
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

    CommandResult cmd(ServerSnapshot snapshot, List<String> argv) {
        return guarded(snapshot, CommandStrings.stringify(argv));
    }

    /** A batch whose every command is refused once this handle's server has been replaced. */
    Batch batch(ServerSnapshot snapshot) {
        long pid = snapshot.serverPid()
                .orElseThrow(() -> new IllegalStateException("a live handle has no server process identity"));
        return new Batch(commands -> guarded(pid, CommandStrings.group(commands), ""));
    }

    /**
     * As {@link #batch(ServerSnapshot)}, fenced against a whole identity read separately.
     *
     * <p>Both halves, because a pid alone is reusable: a different tmux landing on the one just
     * probed would answer as if it were the server the rows are being read from.
     */
    Batch batch(long pid, TmuxVersion version) {
        String fence = "#{&&:#{==:#{pid}," + pid + "},#{==:#{version}," + version + "}}";
        return new Batch(commands -> guarded(pid, fence, CommandStrings.group(commands), ""));
    }

    CommandResult run(ServerSnapshot snapshot, List<String> argv) {
        CommandResult result = cmd(snapshot, argv);
        if (!result.succeeded()) {
            throw new LibTmuxException("tmux " + argv.get(0) + " failed: " + String.join("; ", result.stderr()));
        }
        return result;
    }

    /**
     * Runs several commands in one invocation, so nothing of this caller's happens between them.
     *
     * <p>tmux carries a group to its server as one message and runs it there, so a caller that stops
     * partway cannot leave the group half applied. That is what an operation whose second command
     * cleans up after its first needs.
     */
    CommandResult runTogether(ServerSnapshot snapshot, List<List<String>> commands) {
        return runTogether(snapshot, "", commands);
    }

    /** As {@link #runTogether}, with {@code input} on tmux's standard input for the group to read. */
    CommandResult runTogether(ServerSnapshot snapshot, String input, List<List<String>> commands) {
        CommandResult result = guarded(snapshot, CommandStrings.group(commands), input);
        if (!result.succeeded()) {
            String verbs = commands.stream().map(argv -> argv.get(0)).collect(Collectors.joining(" then "));
            throw new LibTmuxException("tmux " + verbs + " failed: " + String.join("; ", result.stderr()));
        }
        return result;
    }

    /** Refuses to reach a tmux server that is not the one this handle was made against. */
    private CommandResult guarded(ServerSnapshot snapshot, String command) {
        return guarded(snapshot, command, "");
    }

    private CommandResult guarded(ServerSnapshot snapshot, String command, String input) {
        long pid = snapshot.serverPid()
                .orElseThrow(() -> new IllegalStateException("a live handle has no server process identity"));
        return guarded(pid, command, input);
    }

    private CommandResult guarded(long pid, String command, String input) {
        return guarded(pid, "#{==:#{pid}," + pid + "}", command, input);
    }

    private CommandResult guarded(long pid, String fence, String command, String input) {
        String stale = "libtmux-stale-handle-" + pid;
        CommandResult result = cmd(List.of("if-shell", "-F", fence, command, stale), config.defaultTimeout(), input);
        if (!result.succeeded() && result.stderr().stream().anyMatch(line -> line.contains(stale))) {
            throw new ObjectDoesNotExistException("the tmux server this handle belonged to has ended");
        }
        return result;
    }

    CommandResult run(ServerSnapshot snapshot, WindowContext expected, List<String> argv) {
        long pid = snapshot.serverPid()
                .orElseThrow(() -> new IllegalStateException("a live handle has no server process identity"));
        String target = expected.session().value() + ":" + expected.index().value();
        String stale = "libtmux-stale-winlink-" + pid + "-" + expected.window().value();
        String condition = "#{&&:#{==:#{pid}," + pid + "},#{==:#{window_id},"
                + expected.window().value() + "}}";
        CommandResult result =
                cmd(List.of("if-shell", "-F", "-t", target, condition, CommandStrings.stringify(argv), stale));
        if (!result.succeeded() && result.stderr().stream().anyMatch(line -> line.contains(stale))) {
            throw new ObjectDoesNotExistException("window " + expected.window() + " no longer exists here");
        }
        if (!result.succeeded()) {
            throw new LibTmuxException("tmux " + argv.get(0) + " failed: " + String.join("; ", result.stderr()));
        }
        return result;
    }

    ServerSnapshot refresh(ServerSnapshot previous) {
        ServerSnapshot fresh = snapshot();
        if (!identity(previous).equals(identity(fresh))) {
            throw new ObjectDoesNotExistException("the tmux server this handle belonged to has ended");
        }
        return fresh;
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
