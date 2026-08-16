package io.github.libtmux.control;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.PaneId;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.SessionId;
import io.github.libtmux.batch.OperationOutcome;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A tmux client that stays attached and answers one command at a time.
 *
 * <p>This is what a semicolon group cannot be. tmux discards a group after its first failure, so a
 * client has to infer which command failed; here each request is independent and each reply carries
 * the request number that produced it, so a failure discards nothing behind it and attribution is
 * tmux's own.
 *
 * <p>Replies arrive in request order, so a request waiting for its reply is matched by position. A
 * caller that gives up waiting leaves its request in place rather than removing it, because
 * removing it would match the next reply to the wrong request.
 *
 * <p>The reader is a platform thread. A library does not own the virtual-thread scheduler, and a
 * reader that cannot be scheduled is a client that stops answering.
 */
public final class ControlClient implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final long EXIT_MILLIS = 5_000;

    private final Process process;
    private final BufferedWriter requests;
    private final Thread reader;
    private final Queue<Pending> awaiting = new ConcurrentLinkedQueue<>();
    private final List<Consumer<PaneOutput>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ControlEvent>> events = new CopyOnWriteArrayList<>();
    private final ReentrantLock sending = new ReentrantLock();
    private volatile boolean closed;

    private ControlClient(Process process) {
        this.process = process;
        this.requests = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new Thread(this::read, "libtmux-control");
        this.reader.setDaemon(false);
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
        Pending attached = new Pending();
        client.awaiting.add(attached);
        client.reader.start();
        if (!attached.await(DEFAULT_TIMEOUT)) {
            client.close();
            throw new LibTmuxException("the control client did not become ready");
        }
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
     * @return the reply, whose outcome is {@code UNKNOWN} if no answer arrived in time or the client
     *     ended before answering
     */
    public ControlReply send(List<String> argv, Duration timeout) {
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("a command has no words");
        }
        if (isCommandGroup(argv)) {
            // Refused before anything is written, so the stream stays in step and the caller can
            // send the commands one at a time — which is what this carrier is for.
            throw new IllegalArgumentException(
                    "a control-mode request is one command, and this argv is several: " + argv);
        }
        if (closed) {
            throw new IllegalStateException("control client is closed");
        }
        Pending pending = new Pending();
        sending.lock();
        try {
            // Enqueued and written under one lock, so the queue order is the write order.
            awaiting.add(pending);
            requests.write(line(argv));
            requests.newLine();
            requests.flush();
        } catch (IOException e) {
            throw new LibTmuxException("could not write to the control client", e);
        } finally {
            sending.unlock();
        }
        pending.await(timeout);
        return new ControlReply(pending.outcome, pending.lines);
    }

    /**
     * Whether the client is still running.
     *
     * <p>A control client is a client of the server it talks to, so it ends when that server does.
     * A carrier holding one needs to tell "this command failed" from "there is no longer anything
     * to send commands to", which are the same exception until this is asked.
     */
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Subscribes to terminal output tmux pushes.
     *
     * <p>Listeners run on the reader thread, which is also the only thread that resolves replies, so
     * a slow listener delays every answer. One that throws does not end it: the failure goes to the
     * thread's uncaught-exception handler and the remaining listeners are still told.
     */
    public void onOutput(Consumer<PaneOutput> listener) {
        listeners.add(listener);
    }

    /**
     * Subscribes to everything else tmux volunteers: windows appearing, sessions renamed, layouts
     * moving, and the values of any {@link #watch} registered here.
     *
     * <p>Same threading contract as {@link #onOutput}.
     */
    public void onEvent(Consumer<ControlEvent> listener) {
        events.add(listener);
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

    /** Ends the client. Every request still waiting is resolved as {@code UNKNOWN}. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            requests.close();
        } catch (IOException e) {
            // Closing the request stream is how the client is asked to exit; a failure here means
            // it is already gone.
        }
        try {
            if (!process.waitFor(EXIT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(EXIT_MILLIS, TimeUnit.MILLISECONDS);
            }
            reader.join(EXIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        release();
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
        StringBuilder text = new StringBuilder();
        for (String argument : argv) {
            if (text.length() > 0) {
                text.append(' ');
            }
            String literal = argument.endsWith("\\;") ? argument.substring(0, argument.length() - 2) + ';' : argument;
            text.append('\'').append(literal.replace("'", "'\\''")).append('\'');
        }
        return text.toString();
    }

    private void read() {
        List<String> block = new ArrayList<>();
        boolean inBlock = false;
        try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("%begin")) {
                    inBlock = true;
                    block = new ArrayList<>();
                } else if (line.startsWith("%end")) {
                    inBlock = false;
                    complete(OperationOutcome.COMPLETE, block);
                } else if (line.startsWith("%error")) {
                    inBlock = false;
                    complete(OperationOutcome.FAILED, block);
                } else if (inBlock) {
                    block.add(line);
                } else if (line.startsWith("%output ")) {
                    publish(line);
                } else if (line.startsWith("%")) {
                    // Everything else tmux volunteers about its own state. A snapshot is still how
                    // state is read; this only says when reading it again would be worth the trouble.
                    ControlEvent.parse(line).ifPresent(this::announce);
                }
            }
        } catch (IOException e) {
            // The client ended. Everything still waiting is resolved below.
        } finally {
            release();
        }
    }

    private void complete(OperationOutcome outcome, List<String> block) {
        Pending pending = awaiting.poll();
        if (pending != null) {
            pending.settle(outcome, block);
        }
    }

    /** A request with no reply is not a failure; it is an unanswered question. */
    private void release() {
        Pending pending;
        while ((pending = awaiting.poll()) != null) {
            pending.settle(OperationOutcome.UNKNOWN, List.of());
        }
    }

    private void publish(String line) {
        int paneEnd = line.indexOf(' ', "%output ".length());
        if (paneEnd < 0) {
            return;
        }
        PaneOutput output = new PaneOutput(
                new PaneId(line.substring("%output ".length(), paneEnd)), unescape(line.substring(paneEnd + 1)));
        tell(listeners, output);
    }

    private void announce(ControlEvent event) {
        tell(events, event);
    }

    /**
     * This thread also resolves every reply, so one listener's failure must not end it. Reported
     * rather than swallowed, and through the thread's own handler rather than a logger, because a
     * dependency-free core has nowhere else to say it.
     */
    private static <T> void tell(List<Consumer<T>> listeners, T value) {
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(value);
            } catch (RuntimeException e) {
                Thread current = Thread.currentThread();
                current.getUncaughtExceptionHandler().uncaughtException(current, e);
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

    private static final class Pending {

        private final CountDownLatch answered = new CountDownLatch(1);
        private volatile OperationOutcome outcome = OperationOutcome.UNKNOWN;
        private volatile List<String> lines = List.of();

        void settle(OperationOutcome outcome, List<String> lines) {
            this.outcome = outcome;
            this.lines = List.copyOf(lines);
            answered.countDown();
        }

        boolean await(Duration timeout) {
            try {
                return answered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
