package io.github.libtmux.control;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.PaneId;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.SessionId;
import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.internal.ProcessTree;
import io.github.libtmux.internal.Utf8;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.TmuxTimeoutException;
import io.github.libtmux.transport.TmuxTransportException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

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
    private final List<EventSubscription<PaneOutputBytes>> byteSubscriptions = new CopyOnWriteArrayList<>();
    private EventSubscription.@Nullable Termination termination;
    private final List<EventSubscription<PaneOutput>> outputSubscriptions = new CopyOnWriteArrayList<>();
    private final List<EventSubscription<ControlEvent>> eventSubscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean failed;
    private volatile boolean subscriptionsClosed;

    private ControlClient(Process process) {
        this.process = process;
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
     * Attaches a control client to an existing session.
     *
     * <p>Attaching is what makes tmux push {@code %output}: a control client that never attaches is
     * told about command replies and nothing else.
     *
     * @param config which tmux and which server
     * @param session the session to attach to
     */
    public static ControlClient attach(ServerConfig config, SessionId session) {
        return attach(config, session, DEFAULT_TIMEOUT);
    }

    /**
     * Attaches a control client and waits up to the supplied deadline for its opening reply.
     *
     * @param config which tmux and which server
     * @param session the session to attach to
     * @param timeout how long to wait for the client to become ready
     */
    public static ControlClient attach(ServerConfig config, SessionId session, Duration timeout) {
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
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new LibTmuxException("could not start a control client", e);
        }
        ControlClient client = new ControlClient(process);
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
        client.requestJsonLayouts();
        LOG.log(System.Logger.Level.DEBUG, "tmux control client attached to session {0}", session.value());
        return client;
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
        ControlReply reply = writer.exchange(line(argv), timeout);
        // The verb and never its arguments, for the reason the process transport gives: an argument
        // carries what a caller typed, and a log a library opens is no place for it.
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(
                    System.Logger.Level.DEBUG,
                    "tmux control {0} {1} in {2} ms",
                    argv.get(0),
                    reply.outcome().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
        return reply;
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
     * Subscribes to terminal output tmux pushes, retaining at most 16 MiB of UTF-8 text.
     *
     * <p>This compatibility API decodes each chunk independently. Use {@link #subscribeOutputBytes}
     * and {@link PaneOutputDecoder} for characters split across chunks.
     *
     * @param capacity how many values this subscriber can retain before its oldest value is dropped
     * @return a pull subscription owned by the caller
     * @throws IllegalArgumentException if {@code capacity} is not positive
     * @throws IllegalStateException if the client has ended
     */
    public EventSubscription<PaneOutput> subscribeOutput(int capacity) {
        return subscribe(
                outputSubscriptions,
                capacity,
                ControlProtocol.DEFAULT_MAX_REPLY_BYTES,
                value -> value.data().getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * Subscribes to state changes tmux volunteers, retaining at most 16 MiB of UTF-8 fields.
     *
     * @param capacity how many values this subscriber can retain before its oldest value is dropped
     * @return a pull subscription owned by the caller
     * @throws IllegalArgumentException if {@code capacity} is not positive
     * @throws IllegalStateException if the client has ended
     */
    public EventSubscription<ControlEvent> subscribeEvents(int capacity) {
        return subscribeEvents(capacity, ControlProtocol.DEFAULT_MAX_REPLY_BYTES);
    }

    /** Subscribes to original pane bytes with independent event and payload byte limits. */
    public EventSubscription<PaneOutputBytes> subscribeOutputBytes(int capacity, long maxBytes) {
        return subscribe(byteSubscriptions, capacity, maxBytes, PaneOutputBytes::size);
    }

    /** Subscribes to state changes, bounding retained UTF-8 field bytes as well as event count. */
    public EventSubscription<ControlEvent> subscribeEvents(int capacity, long maxBytes) {
        return subscribe(eventSubscriptions, capacity, maxBytes, event -> {
            long bytes = event.kind().getBytes(StandardCharsets.UTF_8).length;
            for (String field : event.fields()) {
                bytes += field.getBytes(StandardCharsets.UTF_8).length;
            }
            return bytes + event.value().orElse("").getBytes(StandardCharsets.UTF_8).length;
        });
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
            closeSubscriptions(new EventSubscription.Termination(EventSubscription.EndReason.CLOSED, Optional.empty()));
            writer.close();
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
        } catch (ControlProtocol.LimitExceeded | ControlLineReader.LimitExceeded | IllegalArgumentException e) {
            closeSubscriptions(
                    new EventSubscription.Termination(EventSubscription.EndReason.PROTOCOL_FAILURE, Optional.of(e)));
        } catch (IOException e) {
            closeSubscriptions(
                    new EventSubscription.Termination(EventSubscription.EndReason.TRANSPORT_FAILURE, Optional.of(e)));
        } finally {
            closeSubscriptions(
                    new EventSubscription.Termination(EventSubscription.EndReason.UNKNOWN, Optional.empty()));
            writer.readerEnded();
        }
    }

    private void drainErrors() {
        try (standardError) {
            standardError.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            // Closing or ending the client closes this channel too.
        }
    }

    private void handleNotification(String line, byte[] bytes) {
        if (line.startsWith("%output ")) {
            offer(byteSubscriptions, PaneOutputBytes.parse(bytes));
            publish(line);
        } else if (line.startsWith("%")) {
            // Everything else tmux volunteers about its own state. A snapshot is still how state
            // is read; this only says when reading it again would be worth the trouble.
            ControlEvent.parse(line).ifPresent(this::announce);
            if (line.equals("%exit") || line.startsWith("%exit ")) {
                closeSubscriptions(new EventSubscription.Termination(
                        EventSubscription.EndReason.CONTROL_EXIT, Optional.of(new IOException(line))));
            }
        }
    }

    private void complete(OperationOutcome outcome, List<String> block) {
        writer.complete(outcome, block);
    }

    private void terminate(TmuxTransportException failure) {
        failed = true;
        closeSubscriptions(
                new EventSubscription.Termination(EventSubscription.EndReason.TRANSPORT_FAILURE, Optional.of(failure)));
        if (!processTree.terminate()) {
            failure.addSuppressed(new IllegalStateException("control process tree was not reclaimed"));
        }
    }

    private void publish(String line) {
        int paneEnd = line.indexOf(' ', "%output ".length());
        if (paneEnd < 0) {
            return;
        }
        PaneOutput output = new PaneOutput(
                new PaneId(line.substring("%output ".length(), paneEnd)), unescape(line.substring(paneEnd + 1)));
        offer(outputSubscriptions, output);
    }

    private <T> EventSubscription<T> subscribe(
            List<EventSubscription<T>> subscriptions, int capacity, long maxBytes, ToLongFunction<T> size) {
        if (subscriptionsClosed) {
            throw new IllegalStateException("control client has ended");
        }
        EventSubscription<T> subscription = new EventSubscription<>(capacity, maxBytes, size, subscriptions::remove);
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

    private synchronized void closeSubscriptions(EventSubscription.Termination reason) {
        EventSubscription.Termination first = termination == null ? reason : termination;
        termination = first;
        subscriptionsClosed = true;
        for (EventSubscription<PaneOutputBytes> subscription : byteSubscriptions) {
            subscription.end(first);
        }
        for (EventSubscription<PaneOutput> subscription : outputSubscriptions) {
            subscription.end(first);
        }
        for (EventSubscription<ControlEvent> subscription : eventSubscriptions) {
            subscription.end(first);
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

    /** tmux writes a byte it cannot print as a three-digit octal escape. */
    static String unescape(String data) {
        if (data.indexOf('\\') < 0) {
            return data;
        }
        StringBuilder text = new StringBuilder(data.length());
        for (int index = 0; index < data.length(); index++) {
            char character = data.charAt(index);
            if (character == '\\' && index + 3 < data.length()) {
                try {
                    text.append((char) Integer.parseInt(data.substring(index + 1, index + 4), 8));
                    index += 3;
                    continue;
                } catch (NumberFormatException e) {
                    // Not an escape after all; the backslash is literal.
                }
            }
            text.append(character);
        }
        return text.toString();
    }
}
