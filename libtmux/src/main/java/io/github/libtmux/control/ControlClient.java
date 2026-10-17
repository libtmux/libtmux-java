package io.github.libtmux.control;

import io.github.libtmux.PaneId;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.SessionId;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.catalog.Kind;
import io.github.libtmux.catalog.Operation;
import io.github.libtmux.exception.CommandRejectedException;
import io.github.libtmux.exception.ControlEndedException;
import io.github.libtmux.exception.DispatchException;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.internal.ControlAcquisition;
import io.github.libtmux.internal.ErrorText;
import io.github.libtmux.internal.LocalSocketPin;
import io.github.libtmux.internal.ProcessTree;
import io.github.libtmux.internal.TmuxFeatures;
import io.github.libtmux.internal.Utf8;
import io.github.libtmux.transport.ControlCarrier;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.OperationObserver;
import io.github.libtmux.transport.OperationReport;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A tmux client that stays attached and answers one command at a time.
 *
 * <p>This is what a semicolon group cannot be. tmux discards a group after its first failure, so a
 * client has to infer which command failed; here each request is independent, so a failure
 * discards nothing behind it.
 *
 * <p>Replies arrive in request order, so the writer sends one request at a time and matches its
 * reply by position. A reply is the first block tmux writes for the request: an acknowledgement,
 * which work the command deferred may outlive. What that command queued, as {@code if-shell}
 * queues its branch, and what a hook ran, arrive as blocks of their own, and none of them is taken
 * for the next request's reply. A deadline before the writer picks a request writes nothing; a
 * deadline after that point ends the client because the next reply could no longer be attributed
 * safely.
 *
 * <p>The reader and writer are platform threads. A library does not own the virtual-thread
 * scheduler: a caller's CPU-bound task that never blocks keeps its carrier, and either thread
 * unable to run stops the client from making progress. The reader only
 * resolves replies and fills bounded subscription buffers; subscriber code runs on the thread that
 * pulls a value.
 *
 * <p><strong>Threads.</strong> {@link #send} may be called from several threads at once: requests
 * queue in the order they arrive and each caller gets its own reply. A subscription is read by one
 * thread at a time.
 *
 * <p><strong>Close it.</strong> The client holds an attached tmux client, which is what makes tmux
 * push output to it, and closing is what detaches that client and stops its threads. A client that
 * is forgotten does not hold the JVM open — its threads are daemons — and the attached tmux client
 * exits once the JVM does, because it reads its commands from a pipe that closes with it. Until then
 * it is listed among the session's clients like any other.
 */
public final class ControlClient implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final ControlCarrier LOCAL = new ControlCarrier() {
        @Override
        public Process start(List<String> command) throws IOException {
            return new ProcessBuilder(command).start();
        }

        @Override
        public PinnedSocket pinSocket(String socketPath, Duration timeout) throws IOException {
            return LocalSocketPin.retain(socketPath);
        }
    };

    /** The same seam as the process transport's, since this client starts its own process. */
    private static final System.Logger LOG = System.getLogger(ControlClient.class.getName());

    private static final long EXIT_MILLIS = 5_000;

    private final ControlAcquisition acquisition;
    private final Process process;
    private final ProcessTree processTree;
    private final InputStream standardOutput;
    private final InputStream standardError;
    private final ControlWriter writer;
    private final Thread reader;
    private final Thread errorReader;
    private final ControlProtocol protocol = new ControlProtocol();
    private final List<EventSubscription<PaneOutput>> outputSubscriptions = new CopyOnWriteArrayList<>();
    // Each pane's output decoded as one stream. Touched only by the reader thread.
    private final java.util.Map<PaneId, Utf8.Stream> paneText = new java.util.HashMap<>();
    private final List<EventSubscription<ControlEvent>> eventSubscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final OperationObserver observer;
    private final AtomicLong ids = new AtomicLong();
    private volatile boolean failed;

    /** Set before subscribers hear of the end, so none of them can find this client alive after it. */
    private volatile boolean readerEnded;

    private volatile boolean subscriptionsClosed;
    private static final int STDERR_LIMIT = 4096;
    private final byte[] stderrBytes = new byte[STDERR_LIMIT];
    private int stderrLength;
    private boolean stderrTruncated;

    private ControlClient(Process process, OperationObserver observer, ControlAcquisition acquisition) {
        this.acquisition = acquisition;
        this.process = process;
        this.observer = observer;
        this.processTree = new ProcessTree(process);
        this.standardOutput = process.getInputStream();
        this.standardError = process.getErrorStream();
        BufferedWriter requests =
                new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.writer = new ControlWriter(requests, ControlWriter.DEFAULT_CAPACITY, this::terminate);
        // Daemons, unlike the process transport's drains. Those let go once idle, which is what
        // stops a forgotten transport holding the JVM; these are attached to a live tmux and are
        // never idle, so the same treatment would not help and a forgotten client kept the JVM alive
        // forever — for a command-line program, indistinguishable from a deadlock. Nothing is lost by
        // letting go at exit: send() blocks its caller until the reply arrives, so a call in flight
        // is held by the thread that made it.
        this.reader = new Thread(this::read, "libtmux-control");
        this.reader.setDaemon(true);
        this.errorReader = new Thread(this::drainErrors, "libtmux-control-stderr");
        this.errorReader.setDaemon(true);
        this.errorReader.start();
    }

    /**
     * Attaches a control client to whatever server now answers this endpoint.
     *
     * <p>Attaching is what makes tmux push {@code %output}: a control client that never attaches is
     * told about command replies and nothing else.
     *
     * <p>This does not check which tmux process answered. A socket can be taken over by a new
     * server that reuses the same session id. {@link io.github.libtmux.Server#control} attaches to
     * the process a capture already named; prefer it.
     *
     * @param config which tmux and which server
     * @param session the session to attach to
     */
    @Operation(Kind.LIFECYCLE)
    public static ControlClient attachUnfenced(ServerConfig config, SessionId session) {
        return attachUnfenced(config, session, DEFAULT_TIMEOUT);
    }

    /**
     * As {@link #attachUnfenced(ServerConfig, SessionId)}, waiting up to the supplied deadline for
     * the client's opening reply.
     *
     * @param config which tmux and which server
     * @param session the session to attach to
     * @param timeout how long to wait for the client to become ready
     */
    @Operation(Kind.LIFECYCLE)
    public static ControlClient attachUnfenced(ServerConfig config, SessionId session, Duration timeout) {
        return connect(LOCAL, config, session, timeout, null);
    }

    /**
     * Attaches, then leaves unless the live server is still the process that was captured.
     *
     * <p>A pid can be reused, so the version tmux reports is part of the check. A mismatch detaches
     * before this client changes the server: layout refresh runs only after the check passes.
     *
     * @param serverPid the tmux process a capture recorded
     * @param serverVersion the version text that process reported, compared as text
     */
    @Operation(Kind.LIFECYCLE)
    public static ControlClient attach(
            ServerConfig config, SessionId session, long serverPid, String serverVersion, Duration timeout) {
        return attach(LOCAL, config, session, serverPid, java.util.OptionalLong.empty(), serverVersion, timeout);
    }

    /**
     * As {@link #attach(ServerConfig, SessionId, long, String, Duration)}, started by the carrier
     * that also starts this realm's commands, and checking the start time a capture recorded too.
     *
     * @param serverStartTime when the captured process started, as {@code #{start_time}} reported it;
     *     compared when present, since a restarted tmux can be given the same pid and version
     */
    @Operation(Kind.LIFECYCLE)
    public static ControlClient attach(
            ControlCarrier carrier,
            ServerConfig config,
            SessionId session,
            long serverPid,
            java.util.OptionalLong serverStartTime,
            String serverVersion,
            Duration timeout) {
        Objects.requireNonNull(serverVersion, "serverVersion");
        if (serverPid < 1) {
            throw new IllegalArgumentException("serverPid is not positive: " + serverPid);
        }
        return connect(carrier, config, session, timeout, new Expected(serverPid, serverStartTime, serverVersion));
    }

    private record Expected(long pid, java.util.OptionalLong start, String version) {}

    private static ControlClient connect(
            ControlCarrier carrier,
            ServerConfig config,
            SessionId session,
            Duration timeout,
            @org.jspecify.annotations.Nullable Expected expected) {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout is not positive");
        ControlAcquisition acquisition = ControlAcquisition.acquire(carrier, config, timeout);
        try {
            if (expected != null) acquisition.verify(expected.pid(), expected.start(), expected.version());
            List<String> command = new ArrayList<>(acquisition.command());
            command.addAll(List.of("-C", "attach-session", "-t", session.value()));
            Utf8.requireEncodableArguments(command);
            acquisition.remaining(false);
            Process process;
            try {
                process = carrier.start(command);
            } catch (IOException failure) {
                throw new DispatchException.Failed(
                        "could not start a control client", DispatchOutcome.NOT_DISPATCHED, failure);
            }
            ControlClient client = new ControlClient(process, config.observer(), acquisition);
            try {
                ControlWriter.Request opening = client.writer.expectInitial(acquisition.remaining(true));
                client.reader.start();
                ControlReply reply = client.writer.await(opening);
                if (reply.outcome() != OperationOutcome.COMPLETE) {
                    throw new CommandRejectedException(
                            "the control client did not become ready" + ErrorText.suffix(reply.lines()),
                            "attach-session",
                            -1,
                            reply.lines());
                }
                client.writer.start();
                // Not over the control line: run-shell -C reports through stderr, which a control reply
                // does not carry, and display-message there would run the user's hooks.
                acquisition.recheck(carrier);
                client.requestJsonLayouts(acquisition.remaining(true));
                client.finishAttach(session);
                return client;
            } catch (RuntimeException failure) {
                client.closeAfterFailure(failure);
                throw failure;
            }
        } catch (RuntimeException failure) {
            try {
                acquisition.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void finishAttach(SessionId session) {
        LOG.log(System.Logger.Level.DEBUG, "tmux control client attached to session {0}", session.value());
    }

    /**
     * Asks tmux to report layouts as JSON on notifications this client receives, matching what a
     * plain client already gets from {@code #{window_layout}} on tmux 3.8+.
     *
     * <p>Without this, this client's own {@code %layout-change} carries the classic string even on a
     * server new enough to write JSON elsewhere: the same window's layout then disagrees depending
     * on which kind of client read it — the mismatch a {@code watch}ed layout format or a parsed
     * notification would otherwise hit. Measured harmless on every supported release: {@code refresh-client -f
     * new-layouts} completes with no error on every supported release, just with nothing to change
     * before 3.8, so this is sent unconditionally rather than gated on a version.
     */
    private void requestJsonLayouts(Duration timeout) {
        ControlReply reply = send(List.of("refresh-client", "-f", "new-layouts"), timeout);
        if (reply.outcome() != OperationOutcome.COMPLETE) {
            LibTmuxException failure = new CommandRejectedException(
                    "could not request JSON layouts on attach" + ErrorText.suffix(reply.lines()),
                    "refresh-client",
                    -1,
                    reply.lines());
            closeAfterFailure(failure);
            throw failure;
        }
    }

    /**
     * Runs one command and waits for its reply.
     *
     * <p>Nested tmux command strings ({@code if-shell}, {@code run-shell -C}, hooks, brace blocks,
     * {@code source-file}) are not checked; do not nest a command this library refuses.
     */
    @Operation(Kind.MUTATION)
    public ControlReply send(String... argv) {
        return send(List.of(argv), DEFAULT_TIMEOUT);
    }

    /**
     * Runs one command and waits for its reply.
     *
     * <p>Nested tmux command strings ({@code if-shell}, {@code run-shell -C}, hooks, brace blocks,
     * {@code source-file}) are not checked; do not nest a command this library refuses.
     */
    @Operation(Kind.MUTATION)
    public ControlReply send(List<String> argv) {
        return send(argv, DEFAULT_TIMEOUT);
    }

    /**
     * Runs one command and waits for its reply.
     *
     * <p>Nested tmux command strings ({@code if-shell}, {@code run-shell -C}, hooks, brace blocks,
     * {@code source-file}) are not checked; do not nest a command this library refuses.
     *
     * @param argv the command, its arguments already separate elements
     * @param timeout how long to wait for tmux to answer
     * @return tmux's reply
     * @throws DispatchException if the request cannot complete; its {@link
     *     DispatchException#outcome() outcome} is {@link DispatchOutcome#NOT_DISPATCHED} until
     *     the writer picks the request and {@link DispatchOutcome#UNKNOWN} afterwards
     */
    @Operation(Kind.MUTATION)
    public ControlReply send(List<String> argv, Duration timeout) {
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("a command has no words");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout is not positive");
        }
        if (closed.get() || failed) {
            throw new IllegalStateException("control client is not usable");
        }
        TmuxFeatures.require(
                TmuxVersion.parse(acquisition.identity().version()), TmuxFeatures.Intent.literal(List.of(argv)));
        long started = System.nanoTime();
        try {
            ControlReply reply = writer.exchange(line(argv), timeout);
            long[] timing = writer.takeTiming(System.nanoTime() - started);
            report(argv.get(0), reply, timing);
            if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
                LOG.log(
                        System.Logger.Level.DEBUG,
                        "tmux control {0} {1} in {2} ms",
                        argv.get(0),
                        reply.outcome()
                                .name()
                                .toLowerCase(java.util.Locale.ROOT)
                                .replace('_', ' '),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            return reply;
        } catch (RuntimeException failure) {
            long[] timing = writer.takeTiming(System.nanoTime() - started);
            DispatchOutcome certainty =
                    failure instanceof DispatchException transport ? transport.outcome() : DispatchOutcome.UNKNOWN;
            try {
                observer.accept(new OperationReport(
                        ids.incrementAndGet(),
                        List.of(argv.get(0)),
                        certainty,
                        OptionalInt.empty(),
                        0,
                        0,
                        "",
                        Duration.ofNanos(timing[0]),
                        Duration.ofNanos(timing[1])));
            } catch (RuntimeException observerFailure) {
                LOG.log(System.Logger.Level.WARNING, "operation observer failed", observerFailure);
            }
            throw failure;
        }
    }

    private void report(String verb, ControlReply reply, long[] timing) {
        int exit = reply.succeeded() ? 0 : 1;
        try {
            observer.accept(new OperationReport(
                    ids.incrementAndGet(),
                    List.of(verb),
                    DispatchOutcome.COMPLETE,
                    OptionalInt.of(exit),
                    reply.lines().size(),
                    0,
                    "",
                    Duration.ofNanos(timing[0]),
                    Duration.ofNanos(timing[1])));
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "operation observer failed", failure);
        }
    }

    /**
     * Whether the client is still running.
     *
     * <p>A control client is a client of the server it talks to, so it ends when that server does.
     * A carrier holding one needs to tell "this command failed" from "there is no longer anything
     * to send commands to", which are the same exception until this is asked.
     */
    @Operation(Kind.CAPTURED)
    public boolean isAlive() {
        return !closed.get() && !failed && !readerEnded && process.isAlive();
    }

    /**
     * Subscribes to terminal output tmux pushes.
     *
     * @param capacity how many values this subscriber can retain before its oldest value is dropped
     * @return a pull subscription owned by the caller
     * @throws IllegalArgumentException if {@code capacity} is not positive
     * @throws IllegalStateException if the client has ended
     */
    @Operation(Kind.STREAM)
    public EventSubscription<PaneOutput> subscribeOutput(int capacity) {
        return subscribe(outputSubscriptions, capacity);
    }

    /**
     * Subscribes to state changes tmux volunteers.
     *
     * @param capacity how many values this subscriber can retain before its oldest value is dropped
     * @return a pull subscription owned by the caller
     * @throws IllegalArgumentException if {@code capacity} is not positive
     * @throws IllegalStateException if the client has ended
     */
    @Operation(Kind.STREAM)
    public EventSubscription<ControlEvent> subscribeEvents(int capacity) {
        return subscribe(eventSubscriptions, capacity);
    }

    /**
     * Asks tmux to report a format whenever its value changes.
     *
     * <p>tmux re-expands the format about once a second and writes a {@code subscription-changed}
     * event only when the result differs from last time, so the comparison happens in the server and
     * a client that subscribes does nothing at all between changes. This is what makes watching a
     * server cost nothing while it is idle.
     *
     * @param name what to call it; registering the same name again replaces the old one
     * @param target {@code %*} for every pane, {@code @*} for every window, a specific {@code %id} or
     *     {@code @id}, or the attached session (pass {@code ""} - see below)
     * @param format a tmux format, such as {@code #{pane_current_command}}
     */
    @Operation(Kind.MUTATION)
    public ControlReply watch(String name, String target, String format) {
        return send("refresh-client", "-B", name + ":" + sessionScopeNormalized(target) + ":" + format);
    }

    /**
     * tmux(1) documents {@code refresh-client -B name:what:format}'s {@code what} as empty,
     * {@code %N}, {@code %*}, {@code @N} or {@code @*} only - a session id or an arbitrary word was
     * never a spelling the manual promises, even though some releases happen to accept one leniently
     * (confirmed against the matrix). On master the same non-empty, non-{@code %}/{@code @} target
     * is accepted by the parser but delivers nothing, silently. The empty string is the one
     * spelling confirmed to mean "the attached session" and to actually fire on every tested release,
     * so anything that does not name a pane or window is normalized to it rather than passed through.
     */
    private static String sessionScopeNormalized(String target) {
        return target.startsWith("%") || target.startsWith("@") ? target : "";
    }

    /** Stops a watch. tmux reads a name with no colon in it as one to remove. */
    @Operation(Kind.MUTATION)
    public ControlReply unwatch(String name) {
        return send("refresh-client", "-B", name);
    }

    /** Ends the client, rejecting queued requests and resolving picked requests as uncertain. */
    @Operation(Kind.LIFECYCLE)
    @Override
    public void close() {
        try {
            boolean closeOwner = closed.compareAndSet(false, true);
            if (closeOwner) {
                processTree.captureDescendants();
                writer.close();
                closeSubscriptions(null);
            }
            boolean reclaimed = processTree.terminate();
            if (!closeOwner) {
                if (!reclaimed) {
                    throw new IllegalStateException("control process tree was not reclaimed");
                }
                return;
            }
            AtomicBoolean interrupted = new AtomicBoolean(Thread.interrupted());
            if (!Thread.currentThread().equals(reader)) {
                join(reader, EXIT_MILLIS, interrupted);
            }
            if (!Thread.currentThread().equals(errorReader)) {
                join(errorReader, EXIT_MILLIS, interrupted);
            }
            join(writer, EXIT_MILLIS, interrupted);
            if (interrupted.get()) {
                Thread.currentThread().interrupt();
            }
            if (!reclaimed) {
                throw new IllegalStateException("control process tree was not reclaimed");
            }
        } finally {
            acquisition.close();
        }
    }

    private void closeAfterFailure(RuntimeException failure) {
        try {
            close();
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    // -------------------------------------------------------------------------------- protocol

    /**
     * tmux parses a control-mode request as one line, so an argument has to survive its lexer.
     * Single quotes preserve everything except a single quote, which is closed, escaped and
     * reopened.
     */
    static String line(List<String> argv) {
        return ControlProtocol.line(argv);
    }

    private void read() {
        Throwable readerFailure = null;
        try (var lines = new ControlLineReader(standardOutput, ControlProtocol.DEFAULT_MAX_REPLY_BYTES)) {
            ControlLineReader.Line line;
            while ((line = lines.readLine()) != null) {
                ControlProtocol.Result result = protocol.accept(line.text(), line.encodedBytes());
                if (result instanceof ControlProtocol.Reply reply) {
                    complete(reply.outcome(), reply.lines(), reply.requested());
                } else if (result instanceof ControlProtocol.Notification notification) {
                    handleNotification(notification.line(), line.bytes());
                }
            }
        } catch (IOException | ControlProtocol.LimitExceeded e) {
            readerFailure = e;
        } finally {
            try {
                acquisition.close();
            } catch (RuntimeException cleanup) {
                if (readerFailure == null) readerFailure = cleanup;
                else readerFailure.addSuppressed(cleanup);
            }
            readerEnded = true;
            closeSubscriptions(
                    readerFailure == null && closed.get()
                            ? null
                            : new ControlEndedException(standardError(), stderrTruncated, readerFailure));
            writer.readerEnded();
        }
    }

    private void drainErrors() {
        try (standardError) {
            byte[] buffer = new byte[512];
            int read;
            while ((read = standardError.read(buffer)) >= 0) {
                synchronized (stderrBytes) {
                    int room = stderrBytes.length - stderrLength;
                    int take = Math.min(room, read);
                    if (take > 0) {
                        System.arraycopy(buffer, 0, stderrBytes, stderrLength, take);
                        stderrLength += take;
                    }
                    if (take < read) {
                        stderrTruncated = true;
                    }
                }
            }
        } catch (IOException e) {
            // Closing or ending the client closes this channel too.
        }
    }

    /**
     * The error text captured from this control process, at most 4096 bytes.
     *
     * <p>Empty when the process wrote none. {@link #standardErrorTruncated()} says the stream
     * continued past that bound. The text is whatever had been read when this is called.
     */
    @Operation(Kind.CAPTURED)
    public String standardError() {
        synchronized (stderrBytes) {
            return new String(stderrBytes, 0, stderrLength, StandardCharsets.UTF_8);
        }
    }

    /** Whether {@link #standardError()} stopped before the process finished writing it. */
    @Operation(Kind.CAPTURED)
    public boolean standardErrorTruncated() {
        synchronized (stderrBytes) {
            return stderrTruncated;
        }
    }

    private void handleNotification(String line, byte[] bytes) {
        if (line.startsWith("%output ")) {
            publish(bytes, "%output ".length());
        } else if (line.startsWith("%extended-output ")) {
            // What %output becomes once this client turns on tmux's flow control, pause-after:
            // the pane, how long the output waited, then the same escaped bytes after " : ".
            publish(bytes, "%extended-output ".length());
        } else if (line.startsWith("%")) {
            // Everything else tmux volunteers about its own state. A snapshot is still how state
            // is read; this only says when reading it again would be worth the trouble.
            ControlEvent.parse(line).ifPresent(this::announce);
        }
    }

    private void complete(OperationOutcome outcome, List<String> block, boolean requested) {
        writer.complete(outcome, block, requested);
    }

    private void terminate(DispatchException failure) {
        failed = true;
        closeSubscriptions(failure);
        if (!processTree.terminate()) {
            failure.addSuppressed(new IllegalStateException("control process tree was not reclaimed"));
        }
    }

    /**
     * One piece of a pane's output. tmux cuts pieces by byte count, so each pane's text is decoded
     * as one stream, and a character cut between two pieces arrives whole with the later one.
     */
    private void publish(byte[] line, int start) {
        int paneEnd = start;
        while (paneEnd < line.length && line[paneEnd] != ' ') {
            paneEnd++;
        }
        int data = start == "%output ".length() ? paneEnd + 1 : separatorEnd(line, paneEnd);
        if (paneEnd == line.length || data > line.length) {
            return;
        }
        PaneId pane = new PaneId(new String(line, start, paneEnd - start, StandardCharsets.US_ASCII));
        byte[] piece = unescape(line, data);
        String text =
                paneText.computeIfAbsent(pane, ignored -> new Utf8.Stream()).decode(piece);
        offer(outputSubscriptions, new PaneOutput(pane, text, ByteBuffer.wrap(piece)));
    }

    /** Where the bytes after the first {@code " : "} from {@code from} begin, or past the end. */
    private static int separatorEnd(byte[] line, int from) {
        for (int index = from; index + 2 < line.length; index++) {
            if (line[index] == ' ' && line[index + 1] == ':' && line[index + 2] == ' ') {
                return index + 3;
            }
        }
        return line.length + 1;
    }

    private <T> EventSubscription<T> subscribe(List<EventSubscription<T>> subscriptions, int capacity) {
        if (subscriptionsClosed) {
            throw new IllegalStateException("control client has ended");
        }
        EventSubscription<T> subscription = new EventSubscription<>(capacity, subscriptions::remove);
        subscriptions.add(subscription);
        if (subscriptionsClosed) {
            subscription.close();
            throw new IllegalStateException("control client has ended");
        }
        return subscription;
    }

    private static <T> void offer(List<EventSubscription<T>> subscriptions, T value) {
        for (EventSubscription<T> subscription : subscriptions) {
            subscription.offer(value);
        }
    }

    private synchronized void closeSubscriptions(@org.jspecify.annotations.Nullable Throwable cause) {
        if (subscriptionsClosed) {
            return;
        }
        subscriptionsClosed = true;
        for (EventSubscription<PaneOutput> subscription : outputSubscriptions) {
            subscription.end(cause);
        }
        for (EventSubscription<ControlEvent> subscription : eventSubscriptions) {
            subscription.end(cause);
        }
    }

    private void announce(ControlEvent event) {
        offer(eventSubscriptions, event);
    }

    private static void join(Thread thread, long millis, AtomicBoolean interrupted) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (thread.isAlive()) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            try {
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }
    }

    private static void join(ControlWriter writer, long millis, AtomicBoolean interrupted) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            try {
                writer.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                return;
            } catch (InterruptedException e) {
                interrupted.set(true);
            } catch (IllegalStateException e) {
                return;
            }
        }
    }

    /** tmux writes a control byte or a backslash as a three-digit octal escape, and the rest raw. */
    static byte[] unescape(byte[] line, int from) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(line.length - from);
        for (int index = from; index < line.length; index++) {
            if (line[index] == '\\' && index + 3 < line.length && octal(line, index + 1)) {
                bytes.write(((line[index + 1] - '0') << 6) | ((line[index + 2] - '0') << 3) | (line[index + 3] - '0'));
                index += 3;
            } else {
                bytes.write(line[index]);
            }
        }
        return bytes.toByteArray();
    }

    private static boolean octal(byte[] line, int from) {
        for (int index = from; index < from + 3; index++) {
            if (line[index] < '0' || line[index] > '7') {
                return false;
            }
        }
        return true;
    }
}
