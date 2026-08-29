package io.github.libtmux.transport;

import io.github.libtmux.internal.ProcessTree;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The default transport: one child process per call, drained by a bounded pool of platform threads.
 *
 * <p>A caller takes one admission permit before launching, and the pool holds exactly two workers
 * per permit, so holding a permit means both drains are already free. Without that coupling a
 * caller can start a child whose pipes nobody is reading, and a child whose pipe fills stops
 * instead of exiting.
 *
 * <p>The caller itself may be a virtual thread: on JDK 21 {@code Process.waitFor} takes a
 * {@link ReentrantLock}, so blocking there releases the carrier. The drains may not be, for two
 * independent reasons. A process pipe read is monitor-locked, and — more decisively — a library
 * does not own the scheduler. Any unrelated code blocking inside a monitor holds a carrier, and
 * drains that need a virtual thread to run would then never run at all.
 *
 * <p>Launching and closing are ordered by an explicit gate rather than a flag, because checking a
 * flag and then acting on it lets a caller start a child after {@code close()} has already decided
 * there was nothing left to destroy.
 */
public final class ProcessTransport implements TmuxTransport {

    private static final int DEFAULT_BOUND = 4;
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final long RECLAIM_MILLIS = 5_000;
    private static final long TERMINATION_SECONDS = 60;
    private static final ProcessStarter SYSTEM_STARTER = command -> new ProcessBuilder(command).start();

    private final Semaphore admission;
    private final ThreadPoolExecutor pumps;
    private final int maxOutputBytes;
    private final ProcessStarter starter;
    private final LongSupplier nanoTime;
    private final Set<RunningProcess> live = ConcurrentHashMap.newKeySet();
    private final Set<RunningProcess> killedByClose = ConcurrentHashMap.newKeySet();

    private final ReentrantLock gate = new ReentrantLock();
    private final Condition quiesced = gate.newCondition();
    private final Condition closeCompleted = gate.newCondition();
    private boolean closed;
    private boolean closeComplete;
    private @Nullable ResourceNotReclaimed closeFailure;
    private int launching;

    /** A transport allowing four concurrent tmux processes. */
    public ProcessTransport() {
        this(DEFAULT_BOUND, DEFAULT_MAX_OUTPUT_BYTES);
    }

    /**
     * @param maxConcurrentProcesses how many tmux processes may run at once
     */
    public ProcessTransport(int maxConcurrentProcesses) {
        this(maxConcurrentProcesses, DEFAULT_MAX_OUTPUT_BYTES);
    }

    /**
     * @param maxConcurrentProcesses how many tmux processes may run at once
     * @param maxOutputBytes maximum bytes accepted from each output channel of one process
     */
    public ProcessTransport(int maxConcurrentProcesses, int maxOutputBytes) {
        this(maxConcurrentProcesses, maxOutputBytes, SYSTEM_STARTER, System::nanoTime);
    }

    ProcessTransport(int maxConcurrentProcesses, int maxOutputBytes, ProcessStarter starter, LongSupplier nanoTime) {
        if (maxConcurrentProcesses < 1) {
            throw new IllegalArgumentException("maxConcurrentProcesses is not positive");
        }
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes is not positive");
        }
        this.admission = new Semaphore(maxConcurrentProcesses);
        this.pumps = (ThreadPoolExecutor) Executors.newFixedThreadPool(2 * maxConcurrentProcesses, factory());
        this.maxOutputBytes = maxOutputBytes;
        this.starter = Objects.requireNonNull(starter, "starter");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public CommandResult execute(CommandRequest request) {
        requireOpen();
        requireDispatchable(request.argv());
        long deadline = deadlineAfter(request.timeout());
        admit(deadline);
        RunningProcess process;
        try {
            process = launch(request, deadline);
        } catch (RuntimeException e) {
            admission.release();
            throw e;
        }
        Drains drains = null;
        try {
            closeQuietly(process.process().getOutputStream(), null);
            drains = submit(process);
            return complete(process, drains, deadline);
        } finally {
            live.remove(process);
            killedByClose.remove(process);
            // A permit asserts that two workers are free, so it goes back only once they are. A
            // cancelled FutureTask reports itself done while its worker is still inside the read.
            if (drains == null || drains.reclaimed()) {
                admission.release();
            }
        }
    }

    @Override
    public void close() {
        AtomicBoolean interrupted = new AtomicBoolean(Thread.interrupted());
        boolean closeOwner;
        @Nullable ResourceNotReclaimed observedFailure;
        gate.lock();
        try {
            if (closed) {
                awaitWhile(closeCompleted, () -> !closeComplete, interrupted);
                closeOwner = false;
                observedFailure = closeFailure;
            } else {
                closed = true;
                awaitWhile(quiesced, () -> launching > 0, interrupted);
                closeOwner = true;
                observedFailure = null;
            }
        } finally {
            gate.unlock();
        }

        if (!closeOwner) {
            restoreInterrupt(interrupted);
            if (observedFailure != null) {
                throw observedFailure;
            }
            return;
        }

        @Nullable ResourceNotReclaimed failure;
        try {
            failure = closeResources(interrupted);
        } catch (RuntimeException e) {
            failure = recordFailure(null, "unexpected failure while closing transport", e);
        }
        gate.lock();
        try {
            closeFailure = failure;
            closeComplete = true;
            closeCompleted.signalAll();
        } finally {
            gate.unlock();
        }
        restoreInterrupt(interrupted);
        if (failure != null) {
            throw failure;
        }
    }

    private @Nullable ResourceNotReclaimed closeResources(AtomicBoolean interrupted) {
        @Nullable ResourceNotReclaimed failure = null;
        for (RunningProcess process : live) {
            // Published before the kill, so the caller parked in waitFor can tell our signal from tmux's.
            killedByClose.add(process);
            try {
                if (!process.tree().terminate()) {
                    failure = recordFailure(failure, "tmux process tree was not reclaimed", null);
                }
            } catch (RuntimeException e) {
                failure = recordFailure(failure, "could not destroy tmux", e);
            }
        }
        try {
            pumps.shutdownNow();
        } catch (RuntimeException e) {
            failure = recordFailure(failure, "could not stop pump workers", e);
        }
        if (!awaitTermination(pumps, TERMINATION_SECONDS, interrupted)) {
            failure = recordFailure(failure, "pump workers did not terminate", null);
        }
        return failure;
    }

    // ---------------------------------------------------------------------------- admission

    private void requireOpen() {
        gate.lock();
        try {
            if (closed) {
                throw new IllegalStateException("transport is closed");
            }
        } finally {
            gate.unlock();
        }
    }

    /** POSIX {@code execve} takes NUL-terminated strings, so an embedded NUL cannot survive. */
    private static void requireDispatchable(List<String> argv) {
        for (int index = 0; index < argv.size(); index++) {
            if (argv.get(index).indexOf('\0') >= 0) {
                throw new IllegalArgumentException("embedded null byte in argv element " + index);
            }
        }
    }

    private void admit(long deadline) {
        long remaining = remainingNanos(deadline);
        if (remaining == 0) {
            throw admissionTimeout();
        }
        try {
            if (!admission.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                throw admissionTimeout();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmuxTransportException("interrupted before dispatch", DispatchOutcome.NOT_DISPATCHED, e);
        }
        if (remainingNanos(deadline) == 0) {
            admission.release();
            throw admissionTimeout();
        }
    }

    /** Starts and registers the child atomically with respect to {@link #close()}. */
    private RunningProcess launch(CommandRequest request, long deadline) {
        gate.lock();
        try {
            if (closed) {
                throw new IllegalStateException("transport is closed");
            }
            if (remainingNanos(deadline) == 0) {
                throw admissionTimeout();
            }
            launching++;
        } finally {
            gate.unlock();
        }
        try {
            Process process = starter.start(request.commandLine());
            RunningProcess running = new RunningProcess(process, new ProcessTree(process));
            live.add(running);
            return running;
        } catch (IOException e) {
            throw new TmuxTransportException("could not start tmux", DispatchOutcome.NOT_DISPATCHED, e);
        } finally {
            gate.lock();
            try {
                if (--launching == 0) {
                    quiesced.signalAll();
                }
            } finally {
                gate.unlock();
            }
        }
    }

    // ------------------------------------------------------------------------------ draining

    private Drains submit(RunningProcess process) {
        CountDownLatch finished = new CountDownLatch(2);
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        try {
            return new Drains(
                    pumps.submit(new Pump(process.process().getInputStream(), maxOutputBytes, finished, failure)),
                    pumps.submit(new Pump(process.process().getErrorStream(), maxOutputBytes, finished, failure)),
                    finished,
                    failure);
        } catch (RejectedExecutionException e) {
            throw terminate(process, "transport closed before draining", e);
        }
    }

    private CommandResult complete(RunningProcess process, Drains drains, long deadline) {
        awaitExitOrFailure(process, drains, deadline);
        if (killedByClose.contains(process)) {
            // This exit status is ours, not tmux's; returning it would read as tmux dying on a signal.
            throw new TmuxTransportException("transport closed while tmux was running", DispatchOutcome.UNKNOWN, null);
        }
        byte[] out = collect(drains.stdout(), process, deadline);
        byte[] err = collect(drains.stderr(), process, deadline);
        return new CommandResult(
                process.process().exitValue(), OutputDecoder.stdoutLines(out), OutputDecoder.stderrLines(err));
    }

    private void awaitExitOrFailure(RunningProcess process, Drains drains, long deadline) {
        try {
            CompletableFuture.anyOf(process.process().onExit(), drains.failure())
                    .get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw terminate(process, "interrupted while awaiting tmux", e);
        } catch (TimeoutException e) {
            throw timeout(process, "tmux exceeded its deadline", e);
        } catch (ExecutionException e) {
            throw terminate(process, "could not await tmux", e.getCause());
        }
        Throwable failure = drains.failure().getNow(null);
        if (failure != null) {
            String message =
                    failure instanceof OutputLimitExceeded exceeded ? exceeded.description() : "could not drain tmux";
            throw terminate(process, message, failure);
        }
    }

    private byte[] collect(Future<byte[]> drain, RunningProcess process, long deadline) {
        try {
            return drain.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw terminate(process, "interrupted while draining tmux", e);
        } catch (TimeoutException e) {
            throw timeout(process, "draining tmux exceeded its deadline", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message =
                    cause instanceof OutputLimitExceeded exceeded ? exceeded.description() : "could not drain tmux";
            throw terminate(process, message, cause);
        }
    }

    private long deadlineAfter(Duration timeout) {
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException e) {
            timeoutNanos = Long.MAX_VALUE;
        }
        return nanoTime.getAsLong() + timeoutNanos;
    }

    private long remainingNanos(long deadline) {
        return Math.max(0, deadline - nanoTime.getAsLong());
    }

    private static TmuxTimeoutException admissionTimeout() {
        return new TmuxTimeoutException("admission timed out", DispatchOutcome.NOT_DISPATCHED, null);
    }

    // --------------------------------------------------------------------------- destruction

    /** Drains are deliberately not cancelled: killing the child is what actually ends the read. */
    private TmuxTransportException terminate(RunningProcess process, String message, @Nullable Throwable cause) {
        return reclaim(process, new TmuxTransportException(message, DispatchOutcome.UNKNOWN, cause));
    }

    private TmuxTimeoutException timeout(RunningProcess process, String message, @Nullable Throwable cause) {
        return reclaim(process, new TmuxTimeoutException(message, cause));
    }

    private <T extends TmuxTransportException> T reclaim(RunningProcess process, T failure) {
        if (!process.tree().terminate()) {
            failure.addSuppressed(new ResourceNotReclaimed("tmux process tree was not reclaimed"));
        }
        return failure;
    }

    private static void awaitWhile(Condition condition, BooleanSupplier waiting, AtomicBoolean interrupted) {
        while (waiting.getAsBoolean()) {
            try {
                condition.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }
    }

    private static boolean awaitTermination(ThreadPoolExecutor executor, long seconds, AtomicBoolean interrupted) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return executor.isTerminated();
            }
            try {
                return executor.awaitTermination(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }
    }

    private static ResourceNotReclaimed recordFailure(
            @Nullable ResourceNotReclaimed failure, String message, @Nullable Throwable cause) {
        ResourceNotReclaimed recorded = new ResourceNotReclaimed(message);
        if (cause != null) {
            recorded.addSuppressed(cause);
        }
        if (failure == null) {
            return recorded;
        }
        failure.addSuppressed(recorded);
        return failure;
    }

    private static void restoreInterrupt(AtomicBoolean interrupted) {
        if (interrupted.get()) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Closeable stream, @Nullable TmuxTransportException failure) {
        try {
            stream.close();
        } catch (IOException e) {
            if (failure != null) {
                failure.addSuppressed(e);
            }
        }
    }

    /** Non-daemon, so an unclosed transport is a visible leak rather than a silent JVM exit. */
    private static ThreadFactory factory() {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread worker = new Thread(runnable, "libtmux-pump-" + index.incrementAndGet());
            worker.setDaemon(false);
            return worker;
        };
    }

    private record Drains(
            Future<byte[]> stdout,
            Future<byte[]> stderr,
            CountDownLatch finished,
            CompletableFuture<Throwable> failure) {
        boolean reclaimed() {
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECLAIM_MILLIS);
            try {
                while (finished.getCount() > 0) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    try {
                        if (finished.await(left, TimeUnit.NANOSECONDS)) {
                            return true;
                        }
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                return true;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private record RunningProcess(Process process, ProcessTree tree) {}

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    private record Pump(InputStream source, int limit, CountDownLatch finished, CompletableFuture<Throwable> failure)
            implements Callable<byte[]> {
        @Override
        public byte[] call() throws IOException {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8_192));
                byte[] buffer = new byte[8_192];
                int total = 0;
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (read > limit - total) {
                        throw new OutputLimitExceeded(limit);
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                byte[] result = output.toByteArray();
                source.close();
                return result;
            } catch (IOException | RuntimeException e) {
                failure.complete(e);
                throw e;
            } finally {
                finished.countDown();
            }
        }
    }

    private static final class OutputLimitExceeded extends IOException {
        private static final long serialVersionUID = 1L;
        private final int limit;

        OutputLimitExceeded(int limit) {
            super("tmux output exceeded the " + limit + " byte channel limit");
            this.limit = limit;
        }

        String description() {
            return "tmux output exceeded the " + limit + " byte channel limit";
        }
    }

    /**
     * A worker could not be recovered. Distinct from {@link IllegalStateException}, which this
     * transport reserves for use after close.
     */
    static final class ResourceNotReclaimed extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        ResourceNotReclaimed(String message) {
            super(message);
        }
    }
}
