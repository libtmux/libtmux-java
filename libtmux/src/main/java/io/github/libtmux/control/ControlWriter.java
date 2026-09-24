package io.github.libtmux.control;

import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.format.Tokens;
import io.github.libtmux.internal.CommandStrings;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.TmuxTimeoutException;
import io.github.libtmux.transport.TmuxTransportException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Owns the process-pipe write and the FIFO that attributes replies to requests.
 *
 * <p>A request's reply is the first block tmux writes for it: an acknowledgement, which deferred
 * work may outlive. But tmux answers every command another one queues with a block of its own, as
 * {@code if-shell} queues its branch and {@code run-shell} reports its job, numbered like any other.
 * So each request line is followed by a second, {@code display-message -p} of a marker unique to it:
 * tmux runs that after everything the request queued, whether the request failed or not. Blocks
 * between a reply and its marker belong to the request already answered, and are not handed to
 * the next one.
 */
final class ControlWriter {

    static final int DEFAULT_CAPACITY = 64;

    private static final String MARKER = "libtmux-reply-" + Tokens.perProcess() + "-";

    private final BufferedWriter output;
    private final ArrayBlockingQueue<Request> waiting;
    private final AtomicReference<@Nullable Request> active = new AtomicReference<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Consumer<TmuxTransportException> failed;
    private final Thread thread;
    private final AtomicLong markers = new AtomicLong();
    // Markers of requests answered whose tails tmux may still be writing. Reader thread only.
    private final java.util.ArrayDeque<String> unanswered = new java.util.ArrayDeque<>();

    ControlWriter(BufferedWriter output, int capacity, Consumer<TmuxTransportException> failed) {
        if (capacity < 1) {
            throw new IllegalArgumentException("control writer capacity is not positive");
        }
        this.output = output;
        this.waiting = new ArrayBlockingQueue<>(capacity, true);
        this.failed = failed;
        // A daemon for the reason the reader is one: this thread waits on a queue that a live client
        // never stops feeding, so it would hold the JVM forever if the client were forgotten.
        this.thread = new Thread(this::run, "libtmux-control-writer");
        this.thread.setDaemon(true);
    }

    /** Records the attach request dispatched by the process invocation. */
    Request expectInitial(Duration timeout) {
        // The attach command's reply is its only one, and tmux flags it as no command of the client's.
        Request initial = new Request("", timeout, Request.State.PICKED, null);
        if (!accepting.get() || !active.compareAndSet(null, initial)) {
            initial.fail(unknown("control client ended before becoming ready", null));
        }
        return initial;
    }

    void start() {
        thread.start();
    }

    ControlReply exchange(String line, Duration timeout) {
        Request request = new Request(line, timeout, Request.State.QUEUED, MARKER + markers.incrementAndGet());
        try {
            if (!accepting.get()) {
                throw notDispatched("control client is not accepting requests", null);
            }
            try {
                if (!waiting.offer(request, request.remainingNanos(), TimeUnit.NANOSECONDS)) {
                    request.cancel(
                            timeout("control request admission timed out", DispatchOutcome.NOT_DISPATCHED, null));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                request.cancel(notDispatched("interrupted before control request dispatch", e));
            }
            if (!accepting.get() && request.cancel(notDispatched("control client closed before dispatch", null))) {
                waiting.remove(request);
            }
            ControlReply reply = await(request);
            TIMING.set(request.timing(System.nanoTime()));
            return reply;
        } catch (RuntimeException failure) {
            TIMING.set(request.timing(System.nanoTime()));
            throw failure;
        }
    }

    private static final ThreadLocal<long[]> TIMING = new ThreadLocal<>();

    /**
     * Queue time, then run time, for the last exchange on this thread; {@code elapsed} as run time
     * when this thread made none.
     */
    long[] takeTiming(long elapsed) {
        long[] timing = TIMING.get();
        TIMING.remove();
        return timing == null ? new long[] {0, elapsed} : timing;
    }

    /**
     * Called by the reader for each reply block, in the order tmux wrote them.
     *
     * @param requested tmux's guard flag: clear for a block a hook ran, which answers no request
     */
    void complete(OperationOutcome outcome, List<String> lines, boolean requested) {
        Request request = active.get();
        if (request != null && request.marker == null) {
            // The attach command's reply, which tmux flags as no command of the client's.
            if (active.compareAndSet(request, null)) {
                request.complete(new ControlReply(outcome, lines));
            }
            return;
        }
        if (!requested) {
            return;
        }
        String line = lines.size() == 1 ? lines.get(0) : "";
        if (line.startsWith(MARKER)) {
            unanswered.remove(line);
            return;
        }
        if (!unanswered.isEmpty()) {
            // Queued by a request already answered: its tail, not the next request's reply.
            return;
        }
        if (request != null && active.compareAndSet(request, null)) {
            unanswered.add(Objects.requireNonNull(request.marker));
            request.complete(new ControlReply(outcome, lines));
        }
    }

    void readerEnded() {
        stop(unknown("control client ended before answering", null), true);
    }

    void close() {
        stop(unknown("control client closed", null), false);
    }

    void join(long timeoutMillis) throws InterruptedException {
        if (Thread.currentThread().equals(thread)) {
            return;
        }
        if (thread.getState() == Thread.State.NEW) {
            closeOutput();
            return;
        }
        thread.join(timeoutMillis);
        if (thread.isAlive()) {
            throw new IllegalStateException("control writer did not stop");
        }
    }

    ControlReply await(Request request) {
        try {
            if (!request.await()) {
                expire(
                        request,
                        timeout("control request timed out", DispatchOutcome.NOT_DISPATCHED, null),
                        timeout("control request timed out", DispatchOutcome.UNKNOWN, null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            expire(
                    request,
                    notDispatched("interrupted before control request dispatch", e),
                    unknown("interrupted while awaiting a control reply", e));
        }
        return request.answer();
    }

    private void expire(Request request, TmuxTransportException beforeDispatch, TmuxTransportException afterDispatch) {
        if (request.cancel(beforeDispatch)) {
            waiting.remove(request);
            return;
        }
        if (request.claimFailure()) {
            try {
                stop(afterDispatch, true);
            } finally {
                request.publishFailure(afterDispatch);
            }
        }
        request.awaitTerminal();
    }

    private void run() {
        try {
            while (accepting.get()) {
                Request request;
                try {
                    request = waiting.take();
                } catch (InterruptedException e) {
                    return;
                }
                if (!accepting.get()) {
                    request.cancel(notDispatched("control client closed before dispatch", null));
                    continue;
                }
                if (request.remainingNanos() == 0) {
                    request.cancel(
                            timeout("control request admission timed out", DispatchOutcome.NOT_DISPATCHED, null));
                    continue;
                }
                if (!request.pick()) {
                    continue;
                }
                if (!active.compareAndSet(null, request)) {
                    halt(request, unknown("control writer already has an active request", null));
                    return;
                }
                if (!accepting.get()) {
                    active.compareAndSet(request, null);
                    request.fail(unknown("control client closed after dispatch", null));
                    return;
                }
                try {
                    output.write(request.line);
                    output.newLine();
                    if (request.marker != null) {
                        output.write(CommandStrings.stringify(List.of("display-message", "-p", request.marker)));
                        output.newLine();
                    }
                    output.flush();
                } catch (IOException e) {
                    halt(request, unknown("could not write to the control client", e));
                    return;
                }
                try {
                    if (!request.await()) {
                        expire(
                                request,
                                timeout("control request timed out", DispatchOutcome.NOT_DISPATCHED, null),
                                timeout("control request timed out", DispatchOutcome.UNKNOWN, null));
                        return;
                    }
                } catch (InterruptedException e) {
                    if (!accepting.get()) {
                        return;
                    }
                    Thread.currentThread().interrupt();
                    halt(request, unknown("control writer interrupted after dispatch", e));
                    return;
                }
            }
        } finally {
            closeOutput();
        }
    }

    private void halt(Request request, TmuxTransportException failure) {
        if (request.claimFailure()) {
            try {
                stop(failure, true);
            } finally {
                request.publishFailure(failure);
            }
        }
    }

    private void stop(TmuxTransportException activeFailure, boolean notifyFailure) {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        List<Request> queuedRequests = new ArrayList<>();
        Request queued;
        while ((queued = waiting.poll()) != null) {
            queuedRequests.add(queued);
        }
        Request dispatched = active.getAndSet(null);
        if (dispatched != null) {
            dispatched.claimFailure();
        }
        try {
            if (notifyFailure) {
                failed.accept(activeFailure);
            }
        } finally {
            thread.interrupt();
            for (Request request : queuedRequests) {
                request.cancel(notDispatched("control client closed before dispatch", null));
            }
            if (dispatched != null) {
                dispatched.publishFailure(activeFailure);
            }
        }
    }

    private void closeOutput() {
        try {
            output.close();
        } catch (IOException e) {
            // Ending the process normally closes this stream first.
        }
    }

    private static TmuxTransportException notDispatched(String message, @Nullable Throwable cause) {
        return new TmuxTransportException(message, DispatchOutcome.NOT_DISPATCHED, cause);
    }

    private static TmuxTransportException unknown(String message, @Nullable Throwable cause) {
        return new TmuxTransportException(message, DispatchOutcome.UNKNOWN, cause);
    }

    private static TmuxTimeoutException timeout(String message, DispatchOutcome outcome, @Nullable Throwable cause) {
        return new TmuxTimeoutException(message, outcome, cause);
    }

    static final class Request {

        private enum State {
            QUEUED,
            PICKED,
            FAILING,
            DONE
        }

        private final String line;
        private final @Nullable String marker;
        private final long started = System.nanoTime();
        private volatile long picked = started;
        private volatile boolean dispatched;
        private final long timeoutNanos;
        private final AtomicReference<State> state;
        private final CountDownLatch answered = new CountDownLatch(1);
        private volatile @Nullable Object answer;

        private Request(String line, Duration timeout, State state, @Nullable String marker) {
            this.line = line;
            this.marker = marker;
            this.timeoutNanos = timeoutNanos(timeout);
            this.state = new AtomicReference<>(state);
            this.dispatched = state == State.PICKED;
        }

        boolean pick() {
            if (!state.compareAndSet(State.QUEUED, State.PICKED)) {
                return false;
            }
            picked = System.nanoTime();
            dispatched = true;
            return true;
        }

        /** Queue time, then run time, up to now. A request never picked spent all of it queued. */
        long[] timing(long now) {
            if (!dispatched) {
                return new long[] {Math.max(0, now - started), 0};
            }
            return new long[] {Math.max(0, picked - started), Math.max(0, now - picked)};
        }

        boolean cancel(TmuxTransportException reason) {
            return finish(State.QUEUED, reason);
        }

        void complete(ControlReply reply) {
            finish(State.PICKED, reply);
        }

        boolean fail(TmuxTransportException reason) {
            if (!claimFailure()) {
                return false;
            }
            publishFailure(reason);
            return true;
        }

        boolean claimFailure() {
            return state.compareAndSet(State.PICKED, State.FAILING);
        }

        void publishFailure(TmuxTransportException reason) {
            finish(State.FAILING, reason);
        }

        private boolean finish(State expected, Object result) {
            if (!state.compareAndSet(expected, State.DONE)) {
                return false;
            }
            answer = result;
            answered.countDown();
            return true;
        }

        long remainingNanos() {
            long elapsed = System.nanoTime() - started;
            return Math.max(0, timeoutNanos - Math.max(0, elapsed));
        }

        boolean await() throws InterruptedException {
            return answered.await(remainingNanos(), TimeUnit.NANOSECONDS);
        }

        ControlReply answer() {
            Object result = answer;
            if (result instanceof TmuxTransportException failure) {
                throw failure;
            }
            if (result instanceof ControlReply reply) {
                return reply;
            }
            throw new IllegalStateException("control request has no answer");
        }

        void awaitTerminal() {
            boolean interrupted = Thread.interrupted();
            while (answered.getCount() > 0) {
                try {
                    answered.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private static long timeoutNanos(Duration timeout) {
            try {
                return timeout.toNanos();
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        }
    }
}
