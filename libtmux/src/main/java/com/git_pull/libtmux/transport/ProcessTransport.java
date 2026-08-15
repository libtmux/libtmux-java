package com.git_pull.libtmux.transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
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
    private static final long GRACEFUL_MILLIS = 250;
    private static final long FORCIBLE_MILLIS = 5_000;
    private static final long DRAIN_FLOOR_MILLIS = 5_000;
    private static final long RECLAIM_MILLIS = 5_000;
    private static final long TERMINATION_SECONDS = 60;
    private static final Duration QUIESCE = Duration.ofSeconds(30);

    private final Semaphore admission;
    private final ThreadPoolExecutor pumps;
    private final Set<Process> live = ConcurrentHashMap.newKeySet();
    private final Set<Process> killedByClose = ConcurrentHashMap.newKeySet();

    private final ReentrantLock gate = new ReentrantLock();
    private final Condition quiesced = gate.newCondition();
    private boolean closed;
    private int launching;

    /** A transport allowing four concurrent tmux processes. */
    public ProcessTransport() {
        this(DEFAULT_BOUND);
    }

    /**
     * @param maxConcurrentProcesses how many tmux processes may run at once
     */
    public ProcessTransport(int maxConcurrentProcesses) {
        if (maxConcurrentProcesses < 1) {
            throw new IllegalArgumentException("maxConcurrentProcesses is not positive");
        }
        this.admission = new Semaphore(maxConcurrentProcesses);
        this.pumps = (ThreadPoolExecutor) Executors.newFixedThreadPool(2 * maxConcurrentProcesses, factory());
        this.pumps.prestartAllCoreThreads();
    }

    @Override
    public CommandResult execute(CommandRequest request) {
        requireOpen();
        requireDispatchable(request.argv());
        long deadline = System.nanoTime() + request.timeout().toNanos();
        admit(deadline);
        Process process;
        try {
            process = launch(request);
        } catch (RuntimeException e) {
            admission.release();
            throw e;
        }
        Drains drains = null;
        try {
            closeQuietly(process.getOutputStream(), null);
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
        gate.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            long remaining = QUIESCE.toNanos();
            while (launching > 0 && remaining > 0) {
                remaining = quiesced.awaitNanos(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            gate.unlock();
        }
        AtomicBoolean interrupted = new AtomicBoolean();
        for (Process process : live) {
            // Published before the kill, so the caller parked in waitFor can tell our signal from tmux's.
            killedByClose.add(process);
            destroyAndAwait(process, interrupted);
        }
        pumps.shutdownNow();
        try {
            if (!pumps.awaitTermination(TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                throw new ResourceNotReclaimed("pump workers did not terminate");
            }
        } catch (InterruptedException e) {
            interrupted.set(true);
        }
        if (interrupted.get()) {
            Thread.currentThread().interrupt();
        }
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
        try {
            if (!admission.tryAcquire(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                throw new TmuxTransportException("admission timed out", DispatchOutcome.NOT_DISPATCHED, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmuxTransportException("interrupted before dispatch", DispatchOutcome.NOT_DISPATCHED, e);
        }
    }

    /** Starts and registers the child atomically with respect to {@link #close()}. */
    private Process launch(CommandRequest request) {
        gate.lock();
        try {
            if (closed) {
                throw new IllegalStateException("transport is closed");
            }
            launching++;
        } finally {
            gate.unlock();
        }
        try {
            Process process = new ProcessBuilder(request.commandLine()).start();
            live.add(process);
            return process;
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

    private Drains submit(Process process) {
        CountDownLatch finished = new CountDownLatch(2);
        try {
            return new Drains(
                    pumps.submit(new Pump(process.getInputStream(), finished)),
                    pumps.submit(new Pump(process.getErrorStream(), finished)),
                    finished);
        } catch (RejectedExecutionException e) {
            throw terminate(process, "transport closed before draining", e);
        }
    }

    private CommandResult complete(Process process, Drains drains, long deadline) {
        boolean exited;
        try {
            exited = process.waitFor(remaining(deadline), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw terminate(process, "interrupted while awaiting tmux", e);
        }
        if (!exited) {
            throw terminate(process, "tmux exceeded its deadline", null);
        }
        if (killedByClose.contains(process)) {
            // This exit status is ours, not tmux's; returning it would read as tmux dying on a signal.
            throw new TmuxTransportException("transport closed while tmux was running", DispatchOutcome.UNKNOWN, null);
        }
        byte[] out = collect(drains.stdout(), process, deadline);
        byte[] err = collect(drains.stderr(), process, deadline);
        return new CommandResult(process.exitValue(), OutputDecoder.stdoutLines(out), OutputDecoder.stderrLines(err));
    }

    private byte[] collect(Future<byte[]> drain, Process process, long deadline) {
        try {
            return drain.get(Math.max(DRAIN_FLOOR_MILLIS, remaining(deadline)), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw terminate(process, "interrupted while draining tmux", e);
        } catch (TimeoutException e) {
            throw terminate(process, "draining tmux exceeded its deadline", e);
        } catch (ExecutionException e) {
            throw terminate(process, "could not drain tmux", e.getCause());
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    // --------------------------------------------------------------------------- destruction

    /** Drains are deliberately not cancelled: killing the child is what actually ends the read. */
    private TmuxTransportException terminate(Process process, String message, @Nullable Throwable cause) {
        AtomicBoolean interrupted = new AtomicBoolean(Thread.interrupted());
        TmuxTransportException failure = new TmuxTransportException(message, DispatchOutcome.UNKNOWN, cause);
        if (!destroyAndAwait(process, interrupted)) {
            failure.addSuppressed(new ResourceNotReclaimed("tmux survived forcible destruction"));
        }
        closeQuietly(process.getInputStream(), failure);
        closeQuietly(process.getErrorStream(), failure);
        if (interrupted.get()) {
            Thread.currentThread().interrupt();
        }
        return failure;
    }

    /**
     * Each wait retries across interruption. Returning early is how a child survives: one interrupt
     * landing in the graceful wait would otherwise skip forcible destruction entirely, and the
     * request's own cleanup then drops the last handle to it.
     */
    private static boolean destroyAndAwait(Process process, AtomicBoolean interrupted) {
        process.destroy();
        if (awaitExit(process, GRACEFUL_MILLIS, interrupted)) {
            return true;
        }
        process.destroyForcibly();
        return awaitExit(process, FORCIBLE_MILLIS, interrupted);
    }

    private static boolean awaitExit(Process process, long millis, AtomicBoolean interrupted) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return !process.isAlive();
            }
            try {
                return process.waitFor(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
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

    private record Drains(Future<byte[]> stdout, Future<byte[]> stderr, CountDownLatch finished) {
        boolean reclaimed() {
            try {
                return finished.await(RECLAIM_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return finished.getCount() == 0;
            }
        }
    }

    private record Pump(InputStream source, CountDownLatch finished) implements Callable<byte[]> {
        @Override
        public byte[] call() throws IOException {
            try {
                return source.readAllBytes();
            } finally {
                finished.countDown();
            }
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
