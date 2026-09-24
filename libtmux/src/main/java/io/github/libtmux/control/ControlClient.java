package io.github.libtmux.control;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.ObjectDoesNotExistException;
import io.github.libtmux.PaneId;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.SessionId;
import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.internal.ProcessTree;
import io.github.libtmux.internal.Utf8;
import io.github.libtmux.transport.ControlCarrier;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.OperationObserver;
import io.github.libtmux.transport.OperationReport;
import io.github.libtmux.transport.TmuxTimeoutException;
import io.github.libtmux.transport.TmuxTransportException;
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
 * client has to infer which command failed; here each request is independent and each reply carries
 * the request number that produced it, so a failure discards nothing behind it and attribution is
 * tmux's own.
 *
 * <p>Replies arrive in request order, so the writer sends one request at a time and matches its
 * reply by position. A deadline before the writer picks a request writes nothing; a deadline after
 * that point ends the client because the next reply could no longer be attributed safely.
 *
 * <p>The reader and writer are platform threads. A library does not own the virtual-thread
 * scheduler, and either one unable to run stops the client from making progress. The reader only
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

    private static final ControlCarrier LOCAL = command -> new ProcessBuilder(command).start();

    /** The same seam as the process transport's, since this client starts its own process. */
    private static final System.Logger LOG = System.getLogger(ControlClient.class.getName());

    private static final long EXIT_MILLIS = 5_000;

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
    private volatile boolean subscriptionsClosed;
    private static final int STDERR_LIMIT = 4096;
    private final byte[] stderrBytes = new byte[STDERR_LIMIT];
    private int stderrLength;
    private boolean stderrTruncated;

    private ControlClient(Process process, OperationObserver observer) {
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
    public static ControlClient attachUnfenced(ServerConfig config, SessionId session, Duration timeout) {
        ControlClient client = connect(LOCAL, config, session, timeout);
        client.finishAttach(session);
        return client;
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
    public static ControlClient attach(
            ServerConfig config, SessionId session, long serverPid, String serverVersion, Duration timeout) {
        return attach(LOCAL, config, session, serverPid, serverVersion, timeout);
    }

    /**
     * As {@link #attach(ServerConfig, SessionId, long, String, Duration)}, started by the carrier
     * that also starts this realm's commands.
     */
    public static ControlClient attach(
            ControlCarrier carrier,
            ServerConfig config,
            SessionId session,
            long serverPid,
            String serverVersion,
            Duration timeout) {
        Objects.requireNonNull(serverVersion, "serverVersion");
        if (serverPid < 1) {
            throw new IllegalArgumentException("serverPid is not positive: " + serverPid);
        }
        ControlClient client = connect(carrier, config, session, timeout);
        try {
            client.confirmIncarnation(serverPid, serverVersion);
        } catch (RuntimeException failure) {
            client.closeAfterFailure(failure);
            throw failure;
        }
        client.finishAttach(session);
        return client;
    }

    private static ControlClient connect(
            ControlCarrier carrier, ServerConfig config, SessionId session, Duration timeout) {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout is not positive");
        }
        List<String> command = new ArrayList<>(config.endpointCommand());
        command.addAll(List.of("-C", "attach-session", "-t", session.value()));
        // The same guard CommandRequest applies to every other process this library starts. A
        // control client's own commands travel as UTF-8 over its standard input and are unaffected,
        // but this argv is encoded by the JVM like any other.
        Utf8.requireEncodableArguments(command);
        Process process;
        try {
            process = carrier.start(command);
        } catch (IOException e) {
            throw new LibTmuxException("could not start a control client", e);
        }
        ControlClient client = new ControlClient(process, config.observer());
        // Attaching produces a reply of its own. It is awaited like any other, which is also what
        // proves the client is up before the first command is written.
        ControlWriter.Request attached = client.writer.expectInitial(timeout);
        client.reader.start();
        ControlReply reply;
        try {
            reply = client.writer.await(attached);
        } catch (TmuxTimeoutException e) {
            client.closeAfterFailure(e);
            throw e;
        } catch (TmuxTransportException e) {
            client.closeAfterFailure(e);
            throw new LibTmuxException("could not attach the control client", e);
        }
        if (reply.outcome() != OperationOutcome.COMPLETE) {
            LibTmuxException failure =
                    new LibTmuxException("the control client did not become ready: " + reply.lines());
            client.closeAfterFailure(failure);
            throw failure;
        }
        client.writer.start();
        return client;
    }

    /** Reads the live server and detaches, via the caller, when it is not the one that was named. */
    private void confirmIncarnation(long expectedPid, String expectedVersion) {
        ControlReply reply = send("display-message", "-p", "#{pid} #{version}");
        if (!reply.succeeded() || reply.lines().size() != 1) {
            throw new LibTmuxException("could not read the control client's server");
        }
        String reported = reply.lines().get(0);
        int space = reported.indexOf(' ');
        long pid;
        try {
            pid = space < 1 ? Long.parseLong(reported) : Long.parseLong(reported.substring(0, space));
        } catch (NumberFormatException e) {
            throw new LibTmuxException("tmux reported a malformed server pid");
        }
        String version = space < 1 ? "" : reported.substring(space + 1);
        if (pid != expectedPid || !version.equals(expectedVersion)) {
            throw new ObjectDoesNotExistException("the tmux server this handle belonged to has ended");
        }
    }

    private void finishAttach(SessionId session) {
        requestJsonLayouts();
        LOG.log(System.Logger.Level.DEBUG, "tmux control client attached to session {0}", session.value());
    }

    /**
     * Asks tmux to report layouts as JSON on notifications this client receives, matching what a
     * plain client already gets from {@code #{window_layout}} on tmux 3.8+.
     *
     * <p>Without this, this client's own {@code %layout-change} carries the classic string even on a
     * server new enough to write JSON elsewhere: the same window's layout then disagrees depending
     * on which kind of client read it — the mismatch a {@code watch}ed layout format or a parsed
     * notification would otherwise hit. Measured harmless back to 3.2a: {@code refresh-client -f
     * new-layouts} completes with no error on every supported release, just with nothing to change
     * before 3.8, so this is sent unconditionally rather than gated on a version.
     */
    private void requestJsonLayouts() {
        ControlReply reply = send("refresh-client", "-f", "new-layouts");
        if (reply.outcome() != OperationOutcome.COMPLETE) {
            LibTmuxException failure =
                    new LibTmuxException("could not request JSON layouts on attach: " + reply.lines());
            closeAfterFailure(failure);
            throw failure;
        }
    }

    /** Runs one command and waits for its reply. */
    public ControlReply send(String... argv) {
        return send(List.of(argv), DEFAULT_TIMEOUT);
    }

    /** Runs one command and waits for its reply. */
    public ControlReply send(List<String> argv) {
        return send(argv, DEFAULT_TIMEOUT);
    }

    /**
     * Runs one command and waits for its reply.
     *
     * @param argv the command, its arguments already separate elements
     * @param timeout how long to wait for tmux to answer
     * @return tmux's reply
     * @throws TmuxTransportException if the request cannot complete; its {@link
     *     TmuxTransportException#outcome() outcome} is {@link DispatchOutcome#NOT_DISPATCHED} until
     *     the writer picks the request and {@link DispatchOutcome#UNKNOWN} afterwards
     */
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
                    failure instanceof TmuxTransportException transport ? transport.outcome() : DispatchOutcome.UNKNOWN;
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
            } catch (RuntimeException ignored) {
                LOG.log(System.Logger.Level.WARNING, "operation observer failed");
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
        } catch (RuntimeException ignored) {
            LOG.log(System.Logger.Level.WARNING, "operation observer failed");
        }
    }

    /**
     * Whether the client is still running.
     *
     * <p>A control client is a client of the server it talks to, so it ends when that server does.
     * A carrier holding one needs to tell "this command failed" from "there is no longer anything
     * to send commands to", which are the same exception until this is asked.
     */
    public boolean isAlive() {
        return !closed.get() && !failed && process.isAlive();
    }

    /**
     * Subscribes to terminal output tmux pushes.
     *
     * @param capacity how many values this subscriber can retain before its oldest value is dropped
     * @return a pull subscription owned by the caller
     * @throws IllegalArgumentException if {@code capacity} is not positive
     * @throws IllegalStateException if the client has ended
     */
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
    public ControlReply watch(String name, String target, String format) {
        return send("refresh-client", "-B", name + ":" + sessionScopeNormalized(target) + ":" + format);
    }

    /**
     * tmux(1) documents {@code refresh-client -B name:what:format}'s {@code what} as empty,
     * {@code %N}, {@code %*}, {@code @N} or {@code @*} only - a session id or an arbitrary word was
     * never a spelling the manual promises, even though 3.2a and 3.7c happen to accept one leniently
     * (confirmed against the matrix). On master the same non-empty, non-{@code %}/{@code @} target
     * is accepted by the parser but delivers nothing, silently. The empty string is the one
     * spelling confirmed to mean "the attached session" and to actually fire on every tested release,
     * so anything that does not name a pane or window is normalized to it rather than passed through.
     */
    private static String sessionScopeNormalized(String target) {
        return target.startsWith("%") || target.startsWith("@") ? target : "";
    }

    /** Stops a watch. tmux reads a name with no colon in it as one to remove. */
    public ControlReply unwatch(String name) {
        return send("refresh-client", "-B", name);
    }

    /** Ends the client, rejecting queued requests and resolving picked requests as uncertain. */
    @Override
    public void close() {
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
                    complete(reply.outcome(), reply.lines());
                } else if (result instanceof ControlProtocol.Notification notification) {
                    handleNotification(notification.line(), line.bytes());
                }
            }
        } catch (IOException | ControlProtocol.LimitExceeded e) {
            readerFailure = e;
        } finally {
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
    public String standardError() {
        synchronized (stderrBytes) {
            return new String(stderrBytes, 0, stderrLength, StandardCharsets.UTF_8);
        }
    }

    /** Whether {@link #standardError()} stopped before the process finished writing it. */
    public boolean standardErrorTruncated() {
        synchronized (stderrBytes) {
            return stderrTruncated;
        }
    }

    private void handleNotification(String line, byte[] bytes) {
        if (line.startsWith("%output ")) {
            publish(bytes);
        } else if (line.startsWith("%")) {
            // Everything else tmux volunteers about its own state. A snapshot is still how state
            // is read; this only says when reading it again would be worth the trouble.
            ControlEvent.parse(line).ifPresent(this::announce);
        }
    }

    private void complete(OperationOutcome outcome, List<String> block) {
        writer.complete(outcome, block);
    }

    private void terminate(TmuxTransportException failure) {
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
    private void publish(byte[] line) {
        int start = "%output ".length();
        int paneEnd = start;
        while (paneEnd < line.length && line[paneEnd] != ' ') {
            paneEnd++;
        }
        if (paneEnd == line.length) {
            return;
        }
        PaneId pane = new PaneId(new String(line, start, paneEnd - start, StandardCharsets.US_ASCII));
        byte[] piece = unescape(line, paneEnd + 1);
        String text =
                paneText.computeIfAbsent(pane, ignored -> new Utf8.Stream()).decode(piece);
        offer(outputSubscriptions, new PaneOutput(pane, text, ByteBuffer.wrap(piece)));
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
