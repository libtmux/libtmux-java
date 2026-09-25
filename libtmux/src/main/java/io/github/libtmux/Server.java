package io.github.libtmux;

import io.github.libtmux.batch.Batch;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.internal.CommandStrings;
import io.github.libtmux.query.FilterExpr;
import io.github.libtmux.query.TmuxFilters;
import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.snapshot.WindowContext;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ControlCarrier;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.OperationObserver;
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
import kotlin.annotations.jvm.ReadOnly;
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
 *
 * <p>Close it anyway. A server that owns its transport holds the threads that drain tmux's output,
 * and those are not daemons, so that a reply being read when a program ends is finished rather than
 * truncated. They do let go once they have been idle, so forgetting to close delays a JVM's exit by
 * seconds instead of preventing it — but only closing releases them at once.
 *
 * <p><strong>Threads.</strong> Share one freely. A server holds nothing a call changes, its transport
 * is thread-safe by contract, and the record of what was typed into each pane is concurrent; calls
 * from several threads run side by side, bounded by the transport's own admission. The same goes
 * for every {@link Session}, {@link Window} and {@link Pane} it hands out: each is an immutable view
 * of one capture, and a method that changes tmux changes tmux rather than the handle. Close it once,
 * after the last call on any thread.
 */
public final class Server implements AutoCloseable {

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final ServerConfig config;
    private final TmuxTransport transport;
    private final boolean owned;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final ServerIdentity identity;
    private final SnapshotCapture capture;

    /**
     * What this library last typed into each pane, so a wait can tell the pane's answer from its
     * own question. Shared with every server derived from this one, because {@link Pane#awaitText}
     * reads through a derived server and has to see what the original typed.
     */
    private final PaneEcho echo;

    private Server(ServerConfig config, TmuxTransport transport, boolean owned) {
        this(config, transport, owned, new PaneEcho());
    }

    private Server(ServerConfig config, TmuxTransport transport, boolean owned, PaneEcho echo) {
        this.config = config;
        this.transport = transport;
        this.owned = owned;
        this.identity = ServerIdentity.of(transport.realm(), config.endpoint());
        this.capture = new SnapshotCapture(this);
        this.echo = echo;
    }

    /** What this library last typed into each of this server's panes. */
    PaneEcho echo() {
        return echo;
    }

    /**
     * Creates a detached session and returns that exact session.
     *
     * <p>tmux reports which session it made, so the result is exact even when another session
     * already carries the same name.
     *
     * <p>Keep {@code :} and {@code .} out of the name. tmux decides what to do with them and changes
     * its mind across the supported range: 3.2a through 3.6 rewrite each one to {@code _}, 3.7
     * refuses the name, and 3.7a onwards keeps it — where a bare {@code -t} target still misreads the
     * delimiter, so {@link #hasSession} and {@link #killSession} resolve the id by comparing names
     * instead of building one. {@link Session#name()} reports what tmux settled on.
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
        CommandResult result = run(spec.argv("#{session_id}", this::versionForCreation));
        List<String> reported = result.stdout();
        if (reported.isEmpty()) {
            throw new LibTmuxException(sessionCreationFailureMessage(result));
        }
        SessionId created = new SessionId(reported.get(0));
        ServerSnapshot fresh = snapshot();
        return fresh.session(created)
                .map(session -> new Session(this, fresh, session))
                .orElseThrow(() -> new ObjectDoesNotExistException("the session just created is already gone"));
    }

    /**
     * tmux exits 0 even when nothing was created: an explicit {@code -S} under a directory that does
     * not exist prints its own {@code error creating ...} and still returns success, and a binary
     * that is not tmux at all can exit 0 with nothing on stdout the same way {@code run} cannot tell
     * apart from a real session id. Reads tmux's own words when it spoke and names the configured
     * binary when it did not, rather than letting an empty list reach {@code SessionId} and throw
     * an unchecked collection exception with neither in it.
     */
    private String sessionCreationFailureMessage(CommandResult result) {
        if (!result.stderr().isEmpty()) {
            return "tmux new-session failed: " + String.join("; ", result.stderr());
        }
        return config.binary() + " exited 0 and reported no session id; is it tmux?";
    }

    /**
     * Whether a session with this name exists.
     *
     * @throws ServerNotRunningException if no daemon is running
     */
    public boolean hasSession(String name) {
        Objects.requireNonNull(name, "name");
        return sessionNamed(name).isPresent();
    }

    /**
     * The id of the session tmux keeps under this name, read from one listing.
     *
     * <p>Not a {@code -t =name} target: tmux reads {@code .} and {@code :} in a target as window and
     * pane separators, and it may have stored the name differently from how it was given.
     */
    private Optional<SessionId> sessionNamed(String name) {
        RowFormat format = RowFormat.of("session_id", "session_name", "version");
        CommandResult result = cmd("list-sessions", "-F", format.template());
        if (!result.succeeded()) {
            // A live server with no sessions lists nothing and exits 0, on 3.2a and 3.7d alike, so
            // any failure is not an answer: no daemon, a socket this user cannot open, or a binary
            // that is not tmux. failed() tells the first apart.
            throw failed("list-sessions", result);
        }
        List<RowFormat.Row> rows = format.rows(result.stdout());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        TmuxVersion version = TmuxVersion.parse(rows.get(0).text("version"));
        rows = format.rows(TmuxFormats.printed(result.stdout(), version));
        for (String stored : TmuxFormats.storedNames(name, version)) {
            for (RowFormat.Row row : rows) {
                if (row.text("session_name").equals(stored)) {
                    return Optional.of(new SessionId(row.text("session_id")));
                }
            }
        }
        return Optional.empty();
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
        SessionId id =
                sessionNamed(name).orElseThrow(() -> new ObjectDoesNotExistException("no session named " + name));
        run(List.of("kill-session", "-t", id.value()));
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
     * @throws ServerNotRunningException if no daemon is there to answer
     * @throws LibTmuxException if one is, and could not be reached — a socket this user cannot
     *     open, or a binary that is not tmux, which starting a server would not fix
     */
    public void requireAlive() {
        CommandResult result = cmd(List.of("display-message", "-p", "#{pid}"), config.defaultTimeout());
        if (result.succeeded()) {
            return;
        }
        // Not simply !isAlive(): that answers false for every refusal, so a socket this user cannot
        // open, or a binary that is not tmux, was reported as a server that is not running — which
        // sends a caller to start one when starting one is not the problem. failed() tells the two
        // apart from what tmux said.
        throw failed("display-message", result);
    }

    /**
     * One sentence for a tmux command that did not succeed.
     *
     * <p>Built in one place because a message that omits the exit status, or says nothing at all
     * when tmux printed nothing, is how a misconfigured binary reads as a tmux that simply refused:
     * pointing a server at {@code /bin/false} used to raise "tmux display-message failed: " and
     * stop there.
     */
    LibTmuxException failed(String command, CommandResult result) {
        return failed(command, result.exitCode(), "exit " + result.exitCode(), result.stderr());
    }

    /** As {@link #failed(String, CommandResult)}, for an outcome no exit code describes. */
    LibTmuxException failed(String command, String status, List<String> stderr) {
        return failed(command, -1, status, stderr);
    }

    /**
     * Both command failures end here so that "no daemon" is decided once. It used to be decided at
     * each site that cared, which is why seven reads and every mutation reported a missing server as
     * an ordinary failure while the fifteen reads that had the check reported it as {@link
     * ServerNotRunningException} — the one thing {@code MIGRATION.md} tells a caller it can catch
     * instead of matching on a message.
     */
    private LibTmuxException failed(String command, int exitCode, String status, List<String> stderr) {
        String message = failure(command, config.binary(), status, stderr);
        return stderr.stream().anyMatch(Server::serverAbsent)
                ? new ServerNotRunningException(message, command, exitCode, stderr)
                : new LibTmuxException(message, null, command, exitCode, stderr);
    }

    /**
     * @param status how the command ended, as an exit code or, inside a group, tmux's own outcome
     *     for it — a batch reports which of its commands never ran, which no single exit code says
     */
    static String failure(String command, String binary, String status, List<String> stderr) {
        String reported = stderr.stream().filter(line -> !line.isBlank()).collect(Collectors.joining("; "));
        return "tmux " + command + " failed (" + status + ")"
                + (reported.isEmpty()
                        ? "; it printed no error, so check that " + binary + " is tmux"
                        : ": " + reported);
    }

    /** Whether a failed command's stderr says the daemon itself is gone, rather than refusing the request. */
    static boolean serverAbsent(String message) {
        return message.contains("no server running")
                || message.contains("server exited unexpectedly")
                || message.contains("(No such file or directory)");
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
        CommandResult result = transport.execute(
                request(List.of(List.of("display-message", "-p", "#{pid}"), List.of("kill-server")), timeout, ""));
        if (result.succeeded()) {
            // Only a process transport runs tmux on this host; another transport's pid belongs to
            // whatever it talks to.
            if (transport instanceof ProcessTransport) {
                awaitExit(result.stdout(), timeout);
            }
            return;
        }
        if (!isAlive(timeout)) {
            return;
        }
        throw new LibTmuxException("could not kill the server: " + String.join("; ", result.stderr()));
    }

    /**
     * tmux answers {@code kill-server} before its daemon exits, and the daemon keeps its socket
     * until every client has gone, so a server started straight afterwards could reach the dying
     * one. Waits only for a process of this user that is tmux, or is already exiting.
     */
    private static void awaitExit(List<String> reported, Duration timeout) {
        if (reported.size() != 1) {
            return;
        }
        long pid;
        try {
            pid = Long.parseLong(reported.get(0).strip());
        } catch (NumberFormatException notAPid) {
            return;
        }
        // A daemon already exiting has no readable command and still has to be waited for; its
        // user stays readable. Another user's process never has a readable command, so the user
        // is what keeps an unrelated pid from being waited on.
        Optional<String> me = ProcessHandle.current().info().user();
        Optional<ProcessHandle> daemon = ProcessHandle.of(pid)
                .filter(handle -> me.isPresent() && handle.info().user().equals(me))
                .filter(handle -> handle.info()
                        .command()
                        .map(command -> command.contains("tmux"))
                        .orElse(true));
        if (daemon.isEmpty()) {
            return;
        }
        try {
            daemon.get().onExit().get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LibTmuxException("interrupted waiting for tmux server " + pid + " to exit", interrupted);
        } catch (java.util.concurrent.TimeoutException late) {
            throw new io.github.libtmux.transport.TmuxTimeoutException(
                    "tmux server " + pid + " was still running " + timeout + " after kill-server", late);
        } catch (java.util.concurrent.ExecutionException unexpected) {
            throw new LibTmuxException("could not wait for tmux server " + pid + " to exit", unexpected.getCause());
        }
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
        return new CommandChain(batch(), this::versionForCreation);
    }

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
        return printed(
                run(List.of("display-message", "-p", "--", expansion(format))).stdout());
    }

    private static final String VERSION_MARK = io.github.libtmux.format.Tokens.perProcess() + "-version";

    /**
     * Printed after what a command printed. tmux ends its output with a line break and the transport
     * drops trailing blank lines, so text that itself ends in line breaks would lose them; with this
     * after it, nothing trailing is blank.
     */
    static final String END_MARK = io.github.libtmux.format.Tokens.perProcess() + "-end";

    /** A format for {@code display-message -p} that {@link #printed(List)} reads back exactly. */
    static String expansion(String format) {
        return versioned(format) + END_MARK;
    }

    /**
     * This format with the server's version expanded ahead of it, so the reply can be decoded
     * without another tmux process. See {@link TmuxFormats#printed(String, TmuxVersion)}.
     */
    static String versioned(String format) {
        return "#{version}" + VERSION_MARK + format;
    }

    /** The expansion of a {@link #versioned} format, as tmux held the text. */
    static String printed(List<String> reported) {
        String all = String.join("\n", reported);
        int mark = all.indexOf(VERSION_MARK);
        String text = mark < 0 ? all : all.substring(mark + VERSION_MARK.length());
        if (mark >= 0) {
            try {
                text = TmuxFormats.printed(text, TmuxVersion.parse(all.substring(0, mark)));
            } catch (RuntimeException notAVersion) {
                // Not tmux's own answer, as from a test double; keep what came back.
            }
        }
        // After the decoding, not before: 3.4 escapes a $ that a letter follows, and the mark may
        // begin with one, so a value ending in $ is only whole once that escape is undone.
        return text.endsWith(END_MARK) ? text.substring(0, text.length() - END_MARK.length()) : text;
    }

    /** As {@link #printed(List)}, for a listing whose every row began with {@link #versioned}. */
    static List<String> printedRows(List<String> rows) {
        List<String> text = new ArrayList<>(rows.size());
        for (String row : rows) {
            text.add(printed(List.of(row)));
        }
        return text;
    }

    /**
     * Runs one read with the server's version printed first in the same invocation, and answers
     * with its output as tmux held the text, and without that version line. Line breaks at
     * the end of the output are kept.
     */
    CommandResult printed(@Nullable ServerSnapshot snapshot, List<String> argv) {
        List<List<String>> group = List.of(
                List.of("display-message", "-p", "#{version}"), argv, List.of("display-message", "-p", END_MARK));
        CommandResult result = snapshot == null
                ? transport.execute(request(group, config.defaultTimeout(), ""))
                : guarded(snapshot, CommandStrings.group(group));
        if (result.stdout().isEmpty()) {
            return result;
        }
        List<String> output = result.stdout().subList(1, result.stdout().size());
        if (!output.isEmpty() && output.getLast().equals(END_MARK)) {
            output = output.subList(0, output.size() - 1);
        }
        try {
            output = TmuxFormats.printed(
                    output, TmuxVersion.parse(result.stdout().get(0)));
        } catch (RuntimeException notAVersion) {
            // Not tmux's own answer, as from a test double; keep what came back.
        }
        return new CommandResult(result.exitCode(), List.copyOf(output), result.stderr());
    }

    /** Shell commands run by tmux, and tmux commands chosen by a shell exit status. */
    public Shell shell() {
        return new Shell(this);
    }

    /** The commands this tmux knows. */
    public Commands commands() {
        return new Commands(this);
    }

    /** Locks every client attached to this server. */
    public void lock() {
        run(List.of("lock-server"));
    }

    /** The server's message log. */
    public MessageLog messageLog() {
        return new MessageLog(this);
    }

    /** The command prompt's history. */
    public Prompt prompt() {
        return new Prompt(this);
    }

    /**
     * Runs a read that tmux would otherwise answer by starting a daemon.
     *
     * <p>{@code list-keys} and {@code list-commands} are answered from the binary's own tables, and
     * tmux starts a server to do it rather than reporting that there is none — so reading the key
     * bindings of an endpoint nothing serves left a daemon behind, which is the opposite of a read.
     * {@code -N} says not to, on every supported release, and tmux then reports the absent server
     * the way every other read does.
     */
    CommandResult withoutStartingServer(List<String> command) {
        List<String> argv = new ArrayList<>(config.endpointCommand());
        argv.add(1, "-N");
        CommandResult result =
                transport.execute(new CommandRequest(argv, List.of(command), config.defaultTimeout(), ""));
        if (result.succeeded()) {
            return result;
        }
        throw failed(command.getFirst(), result);
    }

    /** One of this server's wait-for channels, which is where a signal is sent and waited for. */
    public Channel channel(String name) {
        return new Channel(this, name);
    }

    /** Reads only validated tmux variable names, never caller-authored format syntax. */
    @ReadOnly
    public Map<String, String> variables(List<String> names) {
        return variables(names, this::expand);
    }

    Map<String, String> variables(List<String> names, Function<String, String> expand) {
        Objects.requireNonNull(expand, "expand");
        RowFormat format = RowFormat.of(requireVariableNames(names).toArray(String[]::new));
        // One expansion for every name, not one per name: they are read at the same moment, and a
        // caller asking for eight fields no longer pays for eight tmux processes.
        List<RowFormat.Row> rows =
                format.rows(List.of(expand.apply(format.template()).split("\n", -1)));
        if (rows.size() != 1) {
            throw new LibTmuxException("tmux answered " + rows.size() + " rows for one set of variables");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : names) {
            values.put(name, rows.getFirst().text(name));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * Chosen fields for every pane on the server, from one listing.
     *
     * <p>For what a capture does not carry — {@code pane_tty}, {@code pane_dead_status}, {@code
     * pane_start_command}, any of tmux's pane formats. Asking each pane is a tmux process per pane
     * and a view no single moment held; this is one {@code list-panes}, so every value is from the
     * same instant.
     *
     * <pre>{@code
     * Map<PaneId, Map<String, String>> ttys = server.paneFields(List.of("pane_tty", "pane_dead"));
     * }</pre>
     *
     * @param names tmux format variable names, at most 32
     * @return each pane's values keyed by the names asked for, in tmux's pane order
     * @throws IllegalArgumentException if a name is not a tmux variable name
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the listing otherwise fails
     */
    @ReadOnly
    public Map<PaneId, Map<String, String>> paneFields(List<String> names) {
        List<String> fields = new ArrayList<>(List.of("pane_id"));
        fields.addAll(requireVariableNames(names));
        RowFormat format = RowFormat.of(fields.toArray(String[]::new));
        Map<PaneId, Map<String, String>> panes = new LinkedHashMap<>();
        for (RowFormat.Row row :
                format.rows(printedRows(run(List.of("list-panes", "-a", "-F", versioned(format.template())))
                        .stdout()))) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String name : names) {
                values.put(name, row.text(name));
            }
            panes.put(new PaneId(row.text("pane_id")), Collections.unmodifiableMap(values));
        }
        return Collections.unmodifiableMap(panes);
    }

    private static List<String> requireVariableNames(List<String> names) {
        Objects.requireNonNull(names, "names");
        if (names.isEmpty()) {
            throw new IllegalArgumentException("variable names are empty");
        }
        if (names.size() > 32) {
            throw new IllegalArgumentException("at most 32 tmux variables may be read at once");
        }
        for (String name : names) {
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "invalid tmux variable '" + name + "'; expected [A-Za-z][A-Za-z0-9_]*");
            }
        }
        return names;
    }

    /** Enables or disables mouse handling for sessions on this server. */
    public void setMouseEnabled(boolean enabled) {
        globalOptions().set("mouse", enabled ? "on" : "off");
    }

    WakeReason awaitChannel(String channel, Duration timeout, boolean reserveSignalCapacity)
            throws InterruptedException {
        try {
            CommandRequest request = request(List.of("wait-for", "--", channel), timeout);
            if (reserveSignalCapacity) {
                transport.executeWaiting(request);
            } else {
                transport.execute(request);
            }
        } catch (io.github.libtmux.transport.TmuxTimeoutException e) {
            if (Thread.interrupted()) {
                throw cancelled(channel, e);
            }
            if (reserveSignalCapacity && e.outcome() == DispatchOutcome.NOT_DISPATCHED) {
                throw e;
            }
            // The transport killed the waiting client at the deadline; nothing signalled it.
            return isAlive() ? WakeReason.TIMED_OUT : WakeReason.SERVER_GONE;
        } catch (io.github.libtmux.transport.TmuxTransportException e) {
            if (Thread.interrupted()) {
                throw cancelled(channel, e);
            }
            throw e;
        }
        return isAlive() ? WakeReason.SIGNALLED : WakeReason.SERVER_GONE;
    }

    /**
     * A cancelled wait, told from a wait that failed.
     *
     * <p>A transport cannot raise {@link InterruptedException}: its one method does not declare one.
     * So it re-sets the flag and reports the failure the interrupt caused, and the flag is what says
     * which of the two happened. Reported the way {@link Pane#awaitText} reports it, because a caller
     * cancelling one wait and cancelling the other is doing the same thing.
     */
    private static InterruptedException cancelled(String channel, Throwable cause) {
        InterruptedException interrupted = new InterruptedException("interrupted while waiting on channel " + channel);
        interrupted.initCause(cause);
        return interrupted;
    }

    /** The server's key bindings: {@code prefix} when binding, every table when listing. */
    public Keys keys() {
        return new Keys(this, null);
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
        run(List.of("source-file", "--", file.toString()));
    }

    /** The server-wide options, the ones tmux keeps once per server. */
    public Options options() {
        return Options.server(this);
    }

    /** The global session options every session inherits unless it sets its own. */
    public Options globalOptions() {
        return Options.global(this);
    }

    /**
     * The server's environment, which every session inherits and every new process is given.
     *
     * <p>Set here to change what a pane opened later sees — a refreshed {@code SSH_AUTH_SOCK} after
     * reconnecting, say. A pane already running has its own copy and is not affected.
     */
    public Environment environment() {
        return Environment.global(this);
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
     *
     * @throws ServerNotRunningException if no daemon is running
     */
    public TmuxVersion version() {
        return capture.process()
                .map(SnapshotCapture.ServerProcess::version)
                .orElseThrow(() -> new ServerNotRunningException("no tmux server is answering on this endpoint"));
    }

    TmuxVersion version(ServerSnapshot snapshot) {
        return snapshot.serverVersion().orElseGet(this::version);
    }

    /**
     * The version to gate a session-creation spec against.
     *
     * <p>{@code new-session} is the one command that may be the first thing said to a fresh socket,
     * so asking {@link #version()} — which needs an already-answering daemon — fails exactly when a
     * spec depends on the answer and nothing has started the daemon yet. Preferring the running
     * daemon when there is one keeps {@link #version()}'s own reasoning: a server already up may
     * have been started by a different build than this client is invoking. Only when nothing answers
     * at all does this fall back to {@link #binaryVersion}, which asks the executable directly and
     * needs no server.
     *
     * <p>Reads {@link SnapshotCapture#processForCreation()} directly rather than going through
     * {@link #version()}: a daemon this client is too old to talk to fails the identity probe the
     * same way one that was never started does ("server exited unexpectedly", confirmed against the
     * matrix), and {@link #version()}'s own {@link ServerNotRunningException} does not keep that
     * distinction once raised. Falling back to {@code binaryVersion} for that case would report the
     * configured binary's own version as though it were the daemon's — exactly the guarantee this
     * method exists to keep.
     */
    TmuxVersion versionForCreation() {
        return capture.processForCreation()
                .map(SnapshotCapture.ServerProcess::version)
                .orElseGet(this::binaryVersion);
    }

    /**
     * Which tmux the configured binary is, without asking any server.
     *
     * <p>{@code -V} is handled before tmux touches a socket at all, so it answers even against an
     * endpoint nothing is listening on yet — confirmed against every matrix release, with or without
     * a {@code -f} pointed at a config file that does not exist.
     *
     * @throws LibTmuxException if the binary could not be run or did not report a version
     */
    private TmuxVersion binaryVersion() {
        CommandResult result = cmd(List.of("-V"));
        if (!result.succeeded() || result.stdout().isEmpty()) {
            throw new LibTmuxException("tmux -V did not report a version: " + String.join("; ", result.stderr()));
        }
        String reported = result.stdout().get(0);
        int space = reported.indexOf(' ');
        if (space < 0) {
            throw new LibTmuxException("tmux -V reported an unexpected line: " + reported);
        }
        return TmuxVersion.parse(reported.substring(space + 1));
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
                ? identity.at(snapshot.serverPid().orElseThrow(), snapshot.serverStartTime())
                : identity;
    }

    /** A server over a transport it owns and closes. */
    public static Server open(ServerConfig config) {
        Objects.requireNonNull(config, "config");
        ProcessTransport transport = new ProcessTransport();
        transport.observe(config.observer());
        return new Server(config, transport, true);
    }

    /** A server over a transport the caller owns. Closing this server never closes it. */
    public static Server using(ServerConfig config, TmuxTransport transport) {
        return using(config, transport, new PaneEcho());
    }

    /** As {@link #using(ServerConfig, TmuxTransport)}, with the echo record a gate wants to control. */
    static Server using(ServerConfig config, TmuxTransport transport, PaneEcho echo) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(transport, "transport");
        if (!config.observer().equals(OperationObserver.NONE)) {
            transport.observe(config.observer());
        }
        return new Server(config, transport, false, echo);
    }

    /**
     * This server, with every command bounded by a deadline of the caller's choosing.
     *
     * <p>The per-call deadline. Every read and change made through the result, and through every
     * handle taken from it, gives tmux this long rather than the server's default:
     *
     * <pre>{@code
     * List<String> screen = server.within(Duration.ofMillis(500)).panes().get(0).capture();
     * }</pre>
     *
     * <p>Shares this server's transport, so it has the same identity and its handles compare equal
     * to this server's; closing it releases nothing. {@link #toBuilder} is not how to spell this: a
     * server that owns its transport hands a derived server a transport of its own, which a caller
     * bounding many calls would multiply.
     *
     * @throws IllegalArgumentException if the timeout is not positive
     */
    public Server within(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout is not positive: " + timeout);
        }
        return new Server(config.toBuilder().defaultTimeout(timeout).build(), transport, false, echo);
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
     * Attaches a control client to this session's server, and only to the process this capture
     * named.
     *
     * <p>The client is started by this server's transport. A transport that cannot start processes
     * fails here rather than attaching on the local machine. The process id is checked before the
     * client starts and again on the client itself; a server that has taken over the socket is
     * detached before this client changes it.
     *
     * @param session a session captured from this server
     * @throws ObjectDoesNotExistException if that process is no longer the one on this socket
     * @throws IllegalArgumentException if the session belongs to another server
     * @throws IllegalStateException if this server is closed, or its transport starts no control client
     */
    public ControlClient control(Session session) {
        return control(session, config.defaultTimeout());
    }

    /**
     * As {@link #control(Session)}, with the caller's deadline for the attach and the identity read.
     *
     * @param timeout how long to wait for the client to become ready
     */
    public ControlClient control(Session session, Duration timeout) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(timeout, "timeout");
        if (!session.server().identity().equals(identity())) {
            throw new IllegalArgumentException("session belongs to another server");
        }
        requireOpen();
        ServerSnapshot captured = session.snapshot();
        long pid = captured.serverPid()
                .orElseThrow(() -> new IllegalStateException("a live handle has no server process identity"));
        CommandResult live = guarded(captured, "display-message -p '#{pid} #{version}'", "");
        if (!live.succeeded() || live.stdout().size() != 1) {
            throw failed("display-message", live);
        }
        String reported = live.stdout().get(0);
        int space = reported.indexOf(' ');
        if (space < 1) {
            throw new LibTmuxException("tmux reported a malformed server identity");
        }
        long seen;
        try {
            seen = Long.parseLong(reported.substring(0, space));
        } catch (NumberFormatException e) {
            throw new LibTmuxException("tmux reported a malformed server pid");
        }
        if (seen != pid) {
            throw new ObjectDoesNotExistException("the tmux server this handle belonged to has ended");
        }
        ControlCarrier carrier = transport
                .controlCarrier()
                .orElseThrow(() -> new IllegalStateException("this transport does not start control clients"));
        return ControlClient.attach(
                carrier, config, session.id(), pid, captured.serverStartTime(), reported.substring(space + 1), timeout);
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
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if a listing otherwise fails or the listings cannot form one valid
     *     snapshot
     */
    public ServerSnapshot snapshot() {
        requireOpen();
        return capture.attempt()
                .or(capture::attempt)
                .orElseThrow(() -> new LibTmuxException("tmux server changed during snapshot capture"));
    }

    private ServerSnapshot captured(FilterExpr<?> expression, String... listing) {
        return TmuxFilters.format(expression)
                .flatMap(format -> capture.sessionsWhere(format, listing))
                .orElseGet(this::snapshot);
    }

    /**
     * The sessions this expression matches, captured now.
     *
     * <p>A safe expression is sent as {@code list-sessions -f}. The sessions that come back are still
     * tested with the expression. A relation, an expression tmux cannot apply, or a filtered read that finds
     * nothing, reads the whole server and filters that capture.
     *
     * @return an immutable list in tmux order
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    @ReadOnly
    public List<Session> sessions(FilterExpr<Session> expression) {
        Objects.requireNonNull(expression, "expression");
        ServerSnapshot captured = captured(expression, "list-sessions");
        return captured.sessions().stream()
                .map(session -> new Session(this, captured, session))
                .filter(expression)
                .toList();
    }

    /**
     * Every session, captured now.
     *
     * <p>Returns an immutable list in tmux order. Empty means a live server reported no sessions.
     *
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    @ReadOnly
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
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    @ReadOnly
    public List<Window> windows() {
        ServerSnapshot captured = snapshot();
        return captured.windows().stream()
                .map(window -> new Window(this, captured, window))
                .toList();
    }

    /**
     * The winlinks this expression matches, captured now.
     *
     * <p>A safe expression is sent as {@code list-windows -f}. The winlinks that come back are still
     * tested with the expression. A relation, an expression tmux cannot apply, or a filtered read that finds
     * nothing, reads the whole server and filters that capture.
     *
     * @return an immutable list in tmux order
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    @ReadOnly
    public List<Window> windows(FilterExpr<Window> expression) {
        Objects.requireNonNull(expression, "expression");
        ServerSnapshot captured = captured(expression, "list-windows", "-a");
        return captured.windows().stream()
                .map(window -> new Window(this, captured, window))
                .filter(expression)
                .toList();
    }

    /**
     * Captures every pane on the server.
     *
     * @return an immutable list in tmux order
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    @ReadOnly
    public List<Pane> panes() {
        ServerSnapshot captured = snapshot();
        return captured.panes().stream()
                .map(pane -> new Pane(this, captured, pane))
                .toList();
    }

    /**
     * The panes this expression matches, captured now.
     *
     * <p>A safe expression is sent as {@code list-panes -f}. The panes that come back are still
     * tested with the expression. An expression tmux cannot apply, or a filtered read that finds nothing,
     * reads the whole server and filters that capture.
     *
     * @return an immutable list in tmux order
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    @ReadOnly
    public List<Pane> panes(FilterExpr<Pane> expression) {
        Objects.requireNonNull(expression, "expression");
        ServerSnapshot captured = captured(expression, "list-panes", "-a");
        return captured.panes().stream()
                .map(pane -> new Pane(this, captured, pane))
                .filter(expression)
                .toList();
    }

    /**
     * The session with this name, captured now.
     *
     * <p>A name tmux can compare as a format is read with {@code list-sessions -f} and then only that
     * session's windows and panes. A name containing {@code ,}, {@code #}, <code>{</code>,
     * <code>}</code>, {@code :}, or a backslash falls back to a whole-server capture. The name is
     * matched exactly; tmux would otherwise take a prefix, so asking for {@code build} could answer
     * with {@code build-cache}. It is matched as tmux stores it, which can differ from how it was
     * given: every release doubles a backslash, and releases before 3.7 store {@code .} and
     * {@code :} as {@code _}. The name {@link Session#name} reports is found as well.
     *
     * <p>Empty means a successful capture did not contain that name. Capture failures throw.
     *
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    public Optional<Session> session(String name) {
        Objects.requireNonNull(name, "name");
        ServerSnapshot captured = capture.sessionsNamed(name).orElseGet(this::snapshot);
        return TmuxFormats.storedNames(name, version(captured)).stream()
                .flatMap(stored -> captured.session(stored).stream())
                .findFirst()
                .map(session -> new Session(this, captured, session));
    }

    /**
     * The session with this id, captured now.
     *
     * @return empty only when a successful capture contains no match
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    public Optional<Session> session(SessionId id) {
        Objects.requireNonNull(id, "id");
        return one(id.value(), "session_id");
    }

    private Optional<Session> one(String target, String field) {
        if (!TmuxFilters.literal(target)) {
            ServerSnapshot captured = snapshot();
            return field.equals("session_id")
                    ? captured.session(new SessionId(target)).map(session -> new Session(this, captured, session))
                    : captured.session(target).map(session -> new Session(this, captured, session));
        }
        return capture.oneSession(target, field)
                .flatMap(captured -> field.equals("session_id")
                        ? captured.session(new SessionId(target)).map(session -> new Session(this, captured, session))
                        : captured.session(target).map(session -> new Session(this, captured, session)));
    }

    /**
     * The pane with this id, captured now.
     *
     * @return empty only when a successful capture contains no match
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    public Optional<Pane> pane(PaneId id) {
        Objects.requireNonNull(id, "id");
        if (!TmuxFilters.literal(id.value())) {
            return paneFrom(snapshot(), id);
        }
        return capture.sessionsHolding(id).flatMap(captured -> paneFrom(captured, id));
    }

    private Optional<Pane> paneFrom(ServerSnapshot captured, PaneId id) {
        return captured.panes().stream()
                .filter(pane -> pane.id().equals(id))
                .findFirst()
                .map(pane -> new Pane(this, captured, pane));
    }

    /**
     * The winlink at this exact position, captured now.
     *
     * @return empty only when a successful capture contains no match
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
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
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    @ReadOnly
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
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    @ReadOnly
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
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the capture otherwise fails
     */
    @ReadOnly
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
            throw failed(argv.get(0), result);
        }
        return result;
    }

    CommandResult cmd(ServerSnapshot snapshot, List<String> argv) {
        return guarded(snapshot, CommandStrings.stringify(argv));
    }

    /** A batch whose every command is refused once this handle's server has been replaced. */
    Batch batch(ServerSnapshot snapshot) {
        return new Batch(commands -> guarded(snapshot, CommandStrings.group(commands), ""));
    }

    /**
     * As {@link #batch(ServerSnapshot)}, fenced against an identity read separately.
     *
     * <p>Both halves, because a pid alone is reusable: a tmux started on the pid of the one just
     * probed, as a container's first processes often are, would answer as if it were the server the
     * rows are being read from. Its start time is not the same.
     */
    Batch batch(long pid, long startTime) {
        String fence = incarnation(pid, java.util.OptionalLong.of(startTime));
        return new Batch(commands -> guarded(pid, fence, CommandStrings.group(commands), ""));
    }

    /** A condition true only on the server a capture named: its pid, and its start time when known. */
    static String incarnation(long pid, java.util.OptionalLong startTime) {
        String samePid = "#{==:#{pid}," + pid + "}";
        return startTime.isPresent()
                ? "#{&&:" + samePid + ",#{==:#{start_time}," + startTime.getAsLong() + "}}"
                : samePid;
    }

    CommandResult run(ServerSnapshot snapshot, List<String> argv) {
        CommandResult result = cmd(snapshot, argv);
        if (!result.succeeded()) {
            throw failed(argv.get(0), result);
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
            throw failed(verbs, result);
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
        return guarded(pid, incarnation(pid, snapshot.serverStartTime()), command, input);
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
        String condition = "#{&&:" + incarnation(pid, snapshot.serverStartTime()) + ",#{==:#{window_id},"
                + expected.window().value() + "}}";
        CommandResult result =
                cmd(List.of("if-shell", "-F", "-t", target, condition, CommandStrings.stringify(argv), stale));
        if (!result.succeeded() && result.stderr().stream().anyMatch(line -> line.contains(stale))) {
            throw new ObjectDoesNotExistException("window " + expected.window() + " no longer exists here");
        }
        if (!result.succeeded()) {
            throw failed(argv.get(0), result);
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

        /** Forces 256-color client support; false preserves terminal detection. */
        public Builder force256Colors(boolean force256Colors) {
            config.force256Colors(force256Colors);
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
