package io.github.libtmux.mcp;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.PaneId;
import io.github.libtmux.SessionId;
import io.github.libtmux.snapshot.ServerSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** Keeps MCP resource subscriptions aligned with a changing tmux server. */
final class Watches implements AutoCloseable {
    private static final int SIGNAL_CAPACITY = 64;
    private static final int NOTIFICATION_CAPACITY = 256;
    private static final Duration RETRY_DELAY = Duration.ofMillis(250);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(8);
    private static final long JOIN_MILLIS = 5_000;

    /** What tmux pushes and what the protocol sends can be tested independently. */
    interface Notifier {

        /** Says a resource is no longer what a client last read. */
        void updated(String uri);
    }

    private enum Signal {
        STATE,
        OUTPUT_GAP,
        GENERATION_GAP,
        NOTIFY,
        RETRY,
        STOP
    }

    private final Connection connection;
    private final Map<SessionId, WatchAttachment> attachments = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Signal> signals = new ArrayBlockingQueue<>(SIGNAL_CAPACITY);
    private final NotificationBuffer notifications = new NotificationBuffer(NOTIFICATION_CAPACITY);
    private final AtomicBoolean generationGap = new AtomicBoolean();
    private final AtomicBoolean outputGap = new AtomicBoolean();
    private final AtomicBoolean outageAnnounced = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<@Nullable Notifier> notifier = new AtomicReference<>();
    private final Thread supervisor;
    private volatile ResourceInvalidations.Projection projection;

    private Watches(Connection connection, ResourceInvalidations.Projection projection) {
        this.connection = connection;
        this.projection = projection;
        this.supervisor = Thread.ofVirtual().unstarted(this::supervise);
        this.supervisor.setName("libtmux-mcp-watch-supervisor");
    }

    /** Attaches and buffers before the MCP server is built, without notifying an unbound target. */
    static Watches prepare(Connection connection) {
        ServerSnapshot snapshot = connection.server().snapshot();
        if (snapshot.sessions().isEmpty()) {
            throw new IllegalStateException("watching requires a tmux session to attach to");
        }
        Watches watches = new Watches(connection, ResourceInvalidations.project(snapshot, connection::isOurs));
        try {
            if (!watches.reconcile(snapshot)) {
                throw new IllegalStateException("could not attach every tmux session");
            }
            ServerSnapshot current = connection.server().snapshot();
            if (!watches.reconcile(current)) {
                throw new IllegalStateException("could not attach every current tmux session");
            }
            watches.projection = ResourceInvalidations.project(current, connection::isOurs);
            return watches;
        } catch (RuntimeException | Error e) {
            Cleanup cleanup = new Cleanup(e);
            cleanup.run(watches::close);
            throw new IllegalStateException("could not start the requested tmux watcher", e);
        }
    }

    /** Starts watching after a notifier has a live MCP server to target. */
    void start(Notifier target) {
        if (closed.get()) {
            throw new IllegalStateException("tmux watcher is closed");
        }
        if (!notifier.compareAndSet(null, target) || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("tmux watcher is already started");
        }
        attachments.values().forEach(WatchAttachment::start);
        supervisor.start();
        signal(Signal.STATE);
    }

    /** Convenience entry point for callers that already have a notifier. */
    static Watches start(Connection connection, Notifier notifier) {
        Watches watches = prepare(connection);
        try {
            watches.start(notifier);
            return watches;
        } catch (RuntimeException | Error e) {
            Cleanup cleanup = new Cleanup(e);
            cleanup.run(watches::close);
            throw e;
        }
    }

    private void supervise() {
        boolean retry = false;
        RetryBackoff backoff = new RetryBackoff();
        try {
            while (!closed.get()) {
                Signal first = takeSignal(retry, backoff.delay());
                if (first == Signal.STOP) {
                    return;
                }
                boolean timedRetry = first == Signal.RETRY;
                if (!timedRetry) {
                    backoff.reset();
                }
                boolean state = first == Signal.STATE || first == Signal.GENERATION_GAP;
                boolean lostOutput = first == Signal.OUTPUT_GAP;
                boolean lostState = first == Signal.GENERATION_GAP;
                Signal next;
                while ((next = signals.poll()) != null) {
                    if (next == Signal.STOP) {
                        return;
                    }
                    state |= next == Signal.STATE || next == Signal.GENERATION_GAP;
                    lostOutput |= next == Signal.OUTPUT_GAP;
                    lostState |= next == Signal.GENERATION_GAP;
                }
                lostOutput |= outputGap.getAndSet(false);
                lostState |= generationGap.getAndSet(false);
                if (lostOutput) {
                    announce(ResourceInvalidations.droppedOutput(projection));
                }
                announce(notifications.drain());
                if (state || lostState || retry) {
                    retry = !refresh(lostState);
                    if (!retry) {
                        backoff.reset();
                    } else if (timedRetry) {
                        backoff.failedRetry();
                    }
                }
            }
        } catch (InterruptedException e) {
            if (!closed.get()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Signal takeSignal(boolean retry, Duration delay) throws InterruptedException {
        if (!retry) {
            return signals.take();
        }
        Signal signal = signals.poll(delay.toNanos(), TimeUnit.NANOSECONDS);
        return signal == null ? Signal.RETRY : signal;
    }

    private boolean refresh(boolean lostState) {
        ServerSnapshot snapshot;
        ResourceInvalidations.Projection fresh;
        try {
            snapshot = connection.server().snapshot();
            fresh = ResourceInvalidations.project(snapshot, connection::isOurs);
        } catch (RuntimeException e) {
            if (outageAnnounced.compareAndSet(false, true)) {
                announce(ResourceInvalidations.allKnown(projection));
            }
            generationGap.set(true);
            return false;
        }

        outageAnnounced.set(false);
        boolean reconciled = reconcile(snapshot);
        announce(
                lostState
                        ? ResourceInvalidations.allKnown(projection, fresh)
                        : ResourceInvalidations.between(projection, fresh));
        projection = fresh;
        return reconciled;
    }

    private boolean reconcile(ServerSnapshot snapshot) {
        Set<SessionId> wanted = new LinkedHashSet<>();
        snapshot.sessions().forEach(session -> wanted.add(session.id()));
        for (Map.Entry<SessionId, WatchAttachment> entry : new ArrayList<>(attachments.entrySet())) {
            if (!wanted.contains(entry.getKey()) || !entry.getValue().isAlive()) {
                if (attachments.remove(entry.getKey(), entry.getValue())) {
                    entry.getValue().close();
                }
            }
        }
        for (SessionId session : wanted) {
            if (closed.get()) {
                return false;
            }
            if (!attachments.containsKey(session)) {
                try {
                    WatchAttachment added = attach(session);
                    if (started.get()) {
                        added.start();
                    }
                } catch (LibTmuxException | IllegalStateException e) {
                    generationGap.set(true);
                }
            }
        }
        return !attachments.isEmpty()
                && attachments.keySet().containsAll(wanted)
                && attachments.values().stream().allMatch(WatchAttachment::isAlive);
    }

    private WatchAttachment attach(SessionId session) {
        WatchAttachment added = WatchAttachment.open(this, connection, session);
        WatchAttachment existing = attachments.putIfAbsent(session, added);
        if (existing != null) {
            added.close();
            return existing;
        }
        if (closed.get()) {
            attachments.remove(session, added);
            added.close();
            throw new IllegalStateException("tmux watcher is closed");
        }
        return added;
    }

    void output(PaneId pane) {
        String uri = Resources.paneContentUri(pane);
        if (notifications.offer(uri)) {
            signal(Signal.NOTIFY);
        } else {
            signal(Signal.OUTPUT_GAP);
        }
    }

    void outputDropped() {
        signal(Signal.OUTPUT_GAP);
    }

    void stateChanged() {
        signal(Signal.STATE);
    }

    void stateLost() {
        signal(Signal.GENERATION_GAP);
    }

    void ended(WatchAttachment attachment) {
        if (!closed.get() && attachments.get(attachment.session()) == attachment) {
            signal(Signal.GENERATION_GAP);
        }
    }

    private void signal(Signal signal) {
        if (closed.get() && signal != Signal.STOP) {
            return;
        }
        if (!signals.offer(signal)) {
            if (signal == Signal.OUTPUT_GAP) {
                outputGap.set(true);
            } else {
                generationGap.set(true);
            }
        }
    }

    private void announce(Set<String> uris) {
        Notifier target = notifier.get();
        if (target == null) {
            return;
        }
        for (String uri : uris) {
            try {
                target.updated(uri);
            } catch (RuntimeException e) {
                // A client that stopped listening does not end later notifications.
            }
        }
    }

    /** Whether every currently attached control client is still up. */
    boolean isAlive() {
        return !closed.get()
                && !attachments.isEmpty()
                && attachments.values().stream().allMatch(WatchAttachment::isAlive);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Cleanup cleanup = new Cleanup();
        cleanup.run(() -> signals.offer(Signal.STOP));
        cleanup.run(supervisor::interrupt);
        attachments.values().forEach(cleanup::close);
        attachments.clear();
        if (supervisor.getState() != Thread.State.NEW && !Thread.currentThread().equals(supervisor)) {
            cleanup.run(() -> join(supervisor));
        }
        cleanup.throwIfFailed();
    }

    private static void join(Thread thread) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JOIN_MILLIS);
        while (thread.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static final class RetryBackoff {

        private Duration delay = RETRY_DELAY;

        Duration delay() {
            return delay;
        }

        void failedRetry() {
            long doubled = Math.min(MAX_RETRY_DELAY.toNanos(), delay.toNanos() * 2);
            delay = Duration.ofNanos(doubled);
        }

        void reset() {
            delay = RETRY_DELAY;
        }
    }
}
