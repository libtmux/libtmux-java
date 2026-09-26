package io.github.libtmux.transport;

import io.github.libtmux.exception.DispatchException;
import io.github.libtmux.exception.ServerClosedException;
import io.github.libtmux.internal.CommandStrings;
import io.github.libtmux.internal.ProcessTree;
import io.github.libtmux.internal.Utf8;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The default transport: one child process per call, drained by a bounded pool of platform threads.
 *
 * <p>A caller takes one admission permit before launching, and the pool holds exactly three workers
 * per permit, so holding a permit means both drains and the input pump are already free. Without
 * that coupling a caller can start a child whose pipes nobody is reading, or block forever writing
 * to one that stopped reading.
 *
 * <p>A request declared as waiting also takes one of all but one admission permits. The remaining
 * process stays available for the ordinary request that observes or releases those waits, without
 * increasing the total process or pump bound. An additional waiter is refused before dispatch, so
 * its caller knows it was never registered.
 *
 * <p>The caller itself may be a virtual thread: {@code Process.waitFor} releases its carrier. The
 * drains stay platform threads, although on JDK 25 neither a monitor nor a pipe read pins a virtual
 * thread any more. A virtual thread is always a daemon, and a drain has to finish the reply it is
 * reading rather than be dropped at exit; and a library does not own the scheduler, where a
 * caller's CPU-bound task that never blocks keeps its carrier and a drain waiting behind it lets
 * the pipe fill.
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

    /**
     * How long a pump with nothing to do keeps its thread, and so how long an unclosed transport
     * keeps a JVM alive after its last command. Long enough that an ordinary burst of commands
     * reuses threads rather than making new ones, short enough that a program which forgot to close
     * still exits promptly.
     */
    private static final long IDLE_PUMP_SECONDS = 10;

    private static final ProcessStarter SYSTEM_STARTER = command -> new ProcessBuilder(command).start();

    private static final System.Logger LOG = System.getLogger(ProcessTransport.class.getName());

    private final Semaphore admission;
    private final int bound;
    private final @Nullable Semaphore waitingAdmission;
    private final ThreadPoolExecutor pumps;
    private final int maxOutputBytes;
    private final ProcessStarter starter;
    private final LongSupplier nanoTime;
    private final AtomicLong ids = new AtomicLong();
    private volatile OperationObserver observer = OperationObserver.NONE;
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
     *     ({@code executeWaiting} requires at least two)
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
        this(maxConcurrentProcesses, maxOutputBytes, starter, nanoTime, TimeUnit.SECONDS.toNanos(IDLE_PUMP_SECONDS));
    }

    /** As above, with the idle-pump timeout a test can shorten rather than wait out. */
    ProcessTransport(
            int maxConcurrentProcesses,
            int maxOutputBytes,
            ProcessStarter starter,
            LongSupplier nanoTime,
            long idlePumpNanos) {
        if (maxConcurrentProcesses < 1) {
            throw new IllegalArgumentException("maxConcurrentProcesses is not positive");
        }
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes is not positive");
        }
        this.bound = maxConcurrentProcesses;
        this.admission = new Semaphore(maxConcurrentProcesses);
        this.waitingAdmission = maxConcurrentProcesses == 1 ? null : new Semaphore(maxConcurrentProcesses - 1);
        this.pumps = (ThreadPoolExecutor) Executors.newFixedThreadPool(3 * maxConcurrentProcesses, factory());
        // An idle pump lets go of the JVM. The threads are not daemons on purpose — work in flight
        // has to finish, and a drain abandoned halfway is a truncated reply reported as a whole one
        // — but "in flight" is the point, not "ever used". Without this a caller who forgot to
        // close a transport kept a JVM alive forever after its last command, which for a
        // command-line program is indistinguishable from a deadlock. A pump that is running holds
        // the JVM exactly as before; one that has had nothing to do for this long does not, and the
        // pool makes another the moment there is work.
        this.pumps.setKeepAliveTime(idlePumpNanos, TimeUnit.NANOSECONDS);
        this.pumps.allowCoreThreadTimeOut(true);
        this.maxOutputBytes = maxOutputBytes;
        this.starter = Objects.requireNonNull(starter, "starter");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public Optional<ControlCarrier> controlCarrier() {
        return Optional.of(command -> {
            requireOpen();
            return starter.start(command);
        });
    }

    @Override
    public int admissionBound() {
        return bound;
    }

    @Override
    public void observe(OperationObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public CommandResult execute(CommandRequest request) {
        return execute(request, false);
    }

    @Override
    public CommandResult executeWaiting(CommandRequest request) {
        return execute(request, true);
    }

    /**
     * Runs one request, and says so at {@code DEBUG} on this class's {@link System.Logger}.
     *
     * <p>The seam is the JDK's own, so it costs a consumer no dependency and reaches whatever they
     * already route logging to: {@code java.util.logging} by default, SLF4J or Log4j through their
     * {@code System.LoggerFinder} bridges. Enable {@code DEBUG} on {@code io.github.libtmux} to see
     * every command tmux ran, how long it took and how it ended.
     *
     * <p>Only the command verbs are written, never their arguments. An argument carries session
     * names, pane contents and whatever a caller typed — a password sent to a prompt among them —
     * and none of that belongs in a log a library opens on a caller's behalf.
     */
    private CommandResult execute(CommandRequest asked, boolean waiting) {
        long started = nanoTime.getAsLong();
        Span span = new Span();
        try {
            CommandResult result = dispatch(asked, waiting, span);
            if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
                LOG.log(
                        System.Logger.Level.DEBUG,
                        "tmux {0} exited {1} in {2} ms",
                        verbs(asked),
                        result.exitCode(),
                        elapsedMillis(started));
            }
            report(asked, DispatchOutcome.COMPLETE, OptionalInt.of(result.exitCode()), result, span);
            return result;
        } catch (RuntimeException failure) {
            if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
                String outcome = failure instanceof DispatchException transport
                        ? transport
                                .outcome()
                                .name()
                                .toLowerCase(java.util.Locale.ROOT)
                                .replace('_', ' ')
                        : failure.getClass().getSimpleName();
                LOG.log(
                        System.Logger.Level.DEBUG,
                        "tmux {0} failed ({1}) after {2} ms",
                        verbs(asked),
                        outcome,
                        elapsedMillis(started));
            }
            DispatchOutcome certainty =
                    failure instanceof DispatchException transport ? transport.outcome() : DispatchOutcome.UNKNOWN;
            report(asked, certainty, OptionalInt.empty(), null, span);
            throw failure;
        }
    }

    private void report(
            CommandRequest request,
            DispatchOutcome certainty,
            OptionalInt exitCode,
            @Nullable CommandResult result,
            Span span) {
        List<String> verbs =
                request.commands().stream().map(command -> command.get(0)).toList();
        int stdoutLines = result == null ? 0 : result.stdout().size();
        int stderrLines = result == null ? 0 : result.stderr().size();
        String error = result == null ? "" : OperationReport.bound(result.stderr());
        try {
            observer.accept(new OperationReport(
                    ids.incrementAndGet(),
                    verbs,
                    certainty,
                    exitCode,
                    stdoutLines,
                    stderrLines,
                    error,
                    Duration.ofNanos(Math.max(0, span.queuedNanos)),
                    Duration.ofNanos(Math.max(0, span.runNanos))));
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "operation observer failed", failure);
        }
    }

    private static final class Span {
        private long queuedNanos;
        private long runNanos;
    }

    /** The first word of each command: what ran, without anything a caller put in it. */
    private static String verbs(CommandRequest request) {
        return request.commands().stream()
                .map(command -> command.get(0))
                .collect(java.util.stream.Collectors.joining(" then "));
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private CommandResult dispatch(CommandRequest asked, boolean waiting, Span span) {
        requireOpen();
        requireDispatchable(asked.commands());
        CommandRequest request = carriable(asked);
        @Nullable Semaphore waitingPermit = waiting ? waitingAdmission : null;
        if (waiting && waitingPermit == null) {
            throw new DispatchException.Failed(
                    "transport capacity leaves no process for ordinary work", DispatchOutcome.NOT_DISPATCHED, null);
        }
        long deadline = deadlineAfter(request.timeout());
        long queueStart = nanoTime.getAsLong();
        if (waitingPermit != null) {
            admitWaiting(waitingPermit);
        }
        try {
            admit(admission, deadline, "admission timed out");
        } catch (RuntimeException | Error failure) {
            span.queuedNanos = nanoTime.getAsLong() - queueStart;
            release(waitingPermit);
            throw failure;
        }
        span.queuedNanos = nanoTime.getAsLong() - queueStart;
        long runStart = nanoTime.getAsLong();
        RunningProcess process;
        try {
            process = launch(request, deadline);
        } catch (RuntimeException | Error failure) {
            span.runNanos = nanoTime.getAsLong() - runStart;
            admission.release();
            release(waitingPermit);
            throw failure;
        }
        Pumps runningPumps = null;
        try {
            runningPumps = submit(process, request.input());
            return complete(process, runningPumps, deadline);
        } finally {
            span.runNanos = nanoTime.getAsLong() - runStart;
            live.remove(process);
            killedByClose.remove(process);
            // A permit asserts that three workers are free, so it goes back only once they are. A
            // cancelled FutureTask reports itself done while its worker is still inside the read.
            if (runningPumps == null || runningPumps.reclaimed()) {
                admission.release();
                release(waitingPermit);
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
                throw new ServerClosedException("transport is closed", DispatchOutcome.NOT_DISPATCHED);
            }
        } finally {
            gate.unlock();
        }
    }

    /**
     * The request as a process this JVM starts can carry it.
     *
     * <p>A JVM encodes a child's arguments with the platform's encoding, which the locale decides
     * before {@code main} and nothing changes afterwards. Under {@code LANG=C} — the default in most
     * container images — that is ASCII, and {@code é} would reach tmux as {@code ?}. Standard input
     * has no such limit: this library writes it as UTF-8 itself. So when the commands cannot travel
     * as arguments they travel as a script tmux reads there with {@code source-file -}, which is on
     * every supported release, answers with the same output, exit status and error, and reaches an
     * absent daemon the same way without starting one.
     *
     * <p>Written as one line, quoted as {@code if-shell} already quotes every handle command, so tmux
     * stops at the first failure exactly as it does for arguments: a batch means the same thing
     * either way. Only when standard input is already carrying something is there no second route,
     * and then the text is refused rather than corrupted.
     */
    private static CommandRequest carriable(CommandRequest request) {
        if (request.commands().stream().allMatch(Utf8::encodable)) {
            return request;
        }
        if (!request.input().isEmpty()) {
            for (List<String> command : request.commands()) {
                Utf8.requireEncodableArguments(command);
            }
        }
        return new CommandRequest(
                request.endpoint(),
                List.of(List.of("source-file", "-")),
                request.timeout(),
                CommandStrings.group(request.commands()) + "\n");
    }

    /** POSIX {@code execve} takes NUL-terminated strings, so an embedded NUL cannot survive. */
    private static void requireDispatchable(List<List<String>> commands) {
        for (List<String> argv : commands) {
            for (int index = 0; index < argv.size(); index++) {
                if (argv.get(index).indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("embedded null byte in argv element " + index);
                }
            }
        }
    }

    private void admit(Semaphore permits, long deadline, String timeoutMessage) {
        long remaining = remainingNanos(deadline);
        if (remaining == 0) {
            throw admissionTimeout(timeoutMessage);
        }
        try {
            if (!permits.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                throw admissionTimeout(timeoutMessage);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DispatchException.Failed("interrupted before dispatch", DispatchOutcome.NOT_DISPATCHED, e);
        }
        if (remainingNanos(deadline) == 0) {
            permits.release();
            throw admissionTimeout(timeoutMessage);
        }
    }

    private static void admitWaiting(Semaphore permits) {
        if (!permits.tryAcquire()) {
            throw new DispatchException.Failed(
                    "waiting capacity is full; retry after another wait ends", DispatchOutcome.NOT_DISPATCHED, null);
        }
    }

    private static void release(@Nullable Semaphore permit) {
        if (permit != null) {
            permit.release();
        }
    }

    /** Starts and registers the child atomically with respect to {@link #close()}. */
    private RunningProcess launch(CommandRequest request, long deadline) {
        gate.lock();
        try {
            if (closed) {
                throw new ServerClosedException("transport closed before dispatch", DispatchOutcome.NOT_DISPATCHED);
            }
            if (remainingNanos(deadline) == 0) {
                throw admissionTimeout("admission timed out");
            }
            launching++;
        } finally {
            gate.unlock();
        }
        try {
            Process process = starter.start(request.commandLine());
            RunningProcess running = new RunningProcess(process, new ProcessTree(process), request.idempotence());
            live.add(running);
            return running;
        } catch (IOException e) {
            // The JDK's own message already names the binary and the errno text; dropping it is how a
            // misconfigured binary that fails to exec at all reads as a bare, contentless refusal.
            throw new DispatchException.Failed(
                    "could not start tmux: " + e.getMessage(), DispatchOutcome.NOT_DISPATCHED, e);
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

    private Pumps submit(RunningProcess process, String input) {
        CountDownLatch finished = new CountDownLatch(3);
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        try {
            return new Pumps(
                    pumps.submit(new Pump(process.process().getInputStream(), maxOutputBytes, finished, failure)),
                    pumps.submit(new Pump(process.process().getErrorStream(), maxOutputBytes, finished, failure)),
                    pumps.submit(new InputPump(process.process().getOutputStream(), input, finished)),
                    finished,
                    failure);
        } catch (RejectedExecutionException e) {
            throw terminate(process, "transport closed before draining", e);
        }
    }

    private CommandResult complete(RunningProcess process, Pumps runningPumps, long deadline) {
        awaitExitOrFailure(process, runningPumps, deadline);
        if (killedByClose.contains(process)) {
            // This exit status is ours, not tmux's; returning it would read as tmux dying on a signal.
            throw new ServerClosedException("transport closed while tmux was running", DispatchOutcome.UNKNOWN);
        }
        byte[] out = collect(runningPumps.stdout(), process, deadline);
        byte[] err = collect(runningPumps.stderr(), process, deadline);
        return new CommandResult(
                process.process().exitValue(), OutputDecoder.stdoutLines(out), OutputDecoder.stderrLines(err));
    }

    private void awaitExitOrFailure(RunningProcess process, Pumps runningPumps, long deadline) {
        try {
            CompletableFuture.anyOf(process.process().onExit(), runningPumps.failure())
                    .get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw terminate(process, "interrupted while awaiting tmux", e);
        } catch (TimeoutException e) {
            throw timeout(process, "tmux exceeded its deadline", e);
        } catch (ExecutionException e) {
            throw terminate(process, "could not await tmux", e.getCause());
        }
        Throwable failure = runningPumps.failure().getNow(null);
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

    private static DispatchException.TimedOut admissionTimeout(String message) {
        return new DispatchException.TimedOut(message, DispatchOutcome.NOT_DISPATCHED, null);
    }

    // --------------------------------------------------------------------------- destruction

    /** Pumps are deliberately not cancelled: killing the child is what actually ends pipe I/O. */
    private DispatchException terminate(RunningProcess process, String message, @Nullable Throwable cause) {
        return reclaim(
                process, new DispatchException.Failed(message, DispatchOutcome.UNKNOWN, process.idempotence(), cause));
    }

    private DispatchException.TimedOut timeout(RunningProcess process, String message, @Nullable Throwable cause) {
        return reclaim(
                process,
                new DispatchException.TimedOut(message, DispatchOutcome.UNKNOWN, process.idempotence(), cause));
    }

    private <T extends DispatchException> T reclaim(RunningProcess process, T failure) {
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

    private static void closeQuietly(Closeable stream, @Nullable DispatchException failure) {
        try {
            stream.close();
        } catch (IOException e) {
            if (failure != null) {
                failure.addSuppressed(e);
            }
        }
    }

    /**
     * Non-daemon, so a drain in flight finishes rather than being abandoned halfway and reported as
     * a whole reply. An idle one times out instead, so forgetting to close still lets a JVM exit.
     */
    private static ThreadFactory factory() {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread worker = new Thread(runnable, "libtmux-pump-" + index.incrementAndGet());
            worker.setDaemon(false);
            return worker;
        };
    }

    private record Pumps(
            Future<byte[]> stdout,
            Future<byte[]> stderr,
            Future<?> stdin,
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

    private record RunningProcess(Process process, ProcessTree tree, Idempotence idempotence) {}

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

    private record InputPump(OutputStream target, String input, CountDownLatch finished) implements Runnable {
        @Override
        public void run() {
            try {
                if (!input.isEmpty()) {
                    target.write(input.getBytes(StandardCharsets.UTF_8));
                    target.flush();
                }
            } catch (IOException stoppedReading) {
                // The exit status and stderr say what the child made of incomplete input.
            } finally {
                closeQuietly(target, null);
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
