package io.github.libtmux.control;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.PaneId;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.SessionId;
import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.internal.ProcessTree;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 */
public final class ControlClient implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
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
        this.reader = new Thread(this::read, "libtmux-control");
        this.reader.setDaemon(false);
        this.errorReader = new Thread(this::drainErrors, "libtmux-control-stderr");
        this.errorReader.setDaemon(false);
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
        return client;
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
        if (isCommandGroup(argv)) {
            // Refused before anything is written, so the stream stays in step and the caller can
            // send the commands one at a time — which is what this carrier is for.
            throw new IllegalArgumentException("a control-mode request must contain one command");
        }
        if (closed.get() || failed) {
            throw new IllegalStateException("control client is not usable");
        }
        return writer.exchange(line(argv), timeout);
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
     *     {@code @id}, or anything else for the attached session
     * @param format a tmux format, such as {@code #{pane_current_command}}
     */
    public ControlReply watch(String name, String target, String format) {
        return send("refresh-client", "-B", name + ":" + target + ":" + format);
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
            closeSubscriptions();
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
     * Whether this argv is more than one tmux command.
     *
     * <p>tmux ends a command at a semicolon that ends any argument, not only at one standing alone,
     * and a backslash before it keeps the semicolon instead. That is the rule its own argv parser
     * applies before a command runs, so {@code ["kill-window;", "list-windows"]} is two commands and
     * {@code ["display-message", "-p", "done\\;"]} is one.
     *
     * <p>Public because a carrier has to make this judgement before choosing how to send. Control
     * mode frames a reply per command, so a request of several has several replies and this client
     * can account for only one.
     */
    public static boolean isCommandGroup(List<String> argv) {
        for (String argument : argv) {
            if (argument.endsWith(";") && !argument.endsWith("\\;")) {
                return true;
            }
        }
        return false;
    }

    /**
     * tmux parses a control-mode request as one line, so an argument has to survive its lexer.
     * Single quotes preserve everything except a single quote, which is closed, escaped and
     * reopened.
     *
     * <p>The backslash guarding a trailing semicolon is spent here rather than passed on. It exists
     * for tmux's argv parser, which the process carrier goes through and this one does not, so
     * quoting it would deliver a backslash the other carrier had already consumed and the two would
     * disagree about what the argument was.
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
                    handleNotification(notification.line());
                }
            }
        } catch (IOException | ControlProtocol.LimitExceeded e) {
            // The client ended. Everything still waiting is resolved below.
        } finally {
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

    private void handleNotification(String line) {
        if (line.startsWith("%output ")) {
            publish(line);
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
        closeSubscriptions();
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

    private void closeSubscriptions() {
        subscriptionsClosed = true;
        for (EventSubscription<PaneOutput> subscription : outputSubscriptions) {
            subscription.close();
        }
        for (EventSubscription<ControlEvent> subscription : eventSubscriptions) {
            subscription.close();
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
