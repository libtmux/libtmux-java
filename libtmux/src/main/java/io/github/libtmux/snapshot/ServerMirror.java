package io.github.libtmux.snapshot;

import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.SessionId;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.ControlEvent;
import io.github.libtmux.control.Delivery;
import io.github.libtmux.control.EventSubscription;
import io.github.libtmux.exception.TargetGoneException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * A live copy of one tmux server's sessions, windows, panes and clients, rebuilt whenever tmux
 * announces a change.
 *
 * <p>A control client attached through an anchor session hears what tmux announces. Any
 * announcement, or a {@link Delivery.Gap} in them, starts a fresh {@link Server#snapshot()} — never
 * a patch of the last one, since an announcement says that something changed, not everything that
 * did. Announcements that arrive while a snapshot is being taken collapse into one more rebuild, and
 * a rebuild that finds nothing different publishes nothing. Each published {@link View} carries an
 * epoch one above the last, counted by this mirror; it is not a number tmux keeps.
 *
 * <p>tmux keeps no history of what it announced. When the control client ends, the mirror finds
 * the anchor session again by its id and attaches through it with a fresh snapshot; if the session
 * has gone, the mirror ends with {@link TargetGoneException}, and if the server has, with the
 * failure that said so. A change tmux does not announce to this client, such as a pane retitled in
 * another session, is seen at the next rebuild, or within {@code refreshEvery} when one is given.
 *
 * <p>Thread-safe. One virtual thread per mirror listens and rebuilds; {@link #close()} ends it and
 * the control client with it.
 */
public final class ServerMirror implements AutoCloseable {

    /**
     * One published state of the server.
     *
     * @param epoch how many views this mirror published before this one
     * @param snapshot the server as the rebuild captured it
     */
    public record View(long epoch, ServerSnapshot snapshot) {
        public View {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    private static final int EVENTS = 256;

    private final Server server;
    private final SessionId anchor;
    private final Duration refreshEvery;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition published = lock.newCondition();
    private final Thread listener;

    // Guarded by lock.
    private View view;
    private boolean ended;
    private @Nullable Throwable cause;
    private @Nullable Runnable waiter;
    private long waiterEpoch;

    // Owned by the listener thread, and by close() once it has stopped the listener.
    private @Nullable ControlClient control;
    private @Nullable EventSubscription<ControlEvent> events;
    private volatile boolean closing;

    private ServerMirror(Session anchor, Duration refreshEvery) {
        this.server = anchor.server();
        this.anchor = anchor.id();
        this.refreshEvery = refreshEvery;
        attach(anchor);
        try {
            this.view = new View(0, server.snapshot());
        } catch (RuntimeException failure) {
            detach();
            throw failure;
        }
        this.listener = Thread.ofVirtual().name("libtmux-mirror").unstarted(this::listen);
    }

    /**
     * Mirrors the server {@code anchor} belongs to, listening through a control client attached to
     * {@code anchor}.
     *
     * @throws TargetGoneException if the anchor session or its server has gone
     */
    public static ServerMirror open(Session anchor) {
        return open(anchor, Duration.ZERO);
    }

    /**
     * As {@link #open(Session)}, also rebuilding when nothing has been announced for {@code
     * refreshEvery}, so a change tmux does not announce to this client is seen within that long.
     *
     * @param refreshEvery how long a quiet mirror waits before rebuilding anyway; zero never does
     * @throws IllegalArgumentException if {@code refreshEvery} is negative
     */
    public static ServerMirror open(Session anchor, Duration refreshEvery) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(refreshEvery, "refreshEvery");
        if (refreshEvery.isNegative()) {
            throw new IllegalArgumentException("refreshEvery is negative: " + refreshEvery);
        }
        ServerMirror mirror = new ServerMirror(anchor, refreshEvery);
        mirror.listener.start();
        return mirror;
    }

    /** The latest published view. */
    public View current() {
        lock.lock();
        try {
            return view;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits for a view newer than {@code epoch}.
     *
     * @return the newer view, or empty when {@code timeout} passed first or the mirror ended; {@link
     *     #isEnded()} and {@link #cause()} tell those apart
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public Optional<View> awaitNewer(long epoch, Duration timeout) throws InterruptedException {
        long remaining = timeout.isNegative() ? 0 : nanos(timeout);
        lock.lockInterruptibly();
        try {
            while (view.epoch() <= epoch && !ended && remaining > 0) {
                remaining = published.awaitNanos(remaining);
            }
            return view.epoch() > epoch ? Optional.of(view) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Arms a one-shot wakeup for when a view newer than {@code epoch} is published or the mirror ends.
     *
     * <p>Runs {@code callback} at once, on this thread, when such a view already exists or the mirror
     * has ended; otherwise once, on the mirror's own thread. The callback must not block.
     *
     * @throws IllegalStateException if a callback is already armed
     */
    public void onNewer(long epoch, Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        boolean now;
        lock.lock();
        try {
            if (waiter != null) {
                throw new IllegalStateException("a callback is already armed; clearNewer() first");
            }
            now = view.epoch() > epoch || ended;
            if (!now) {
                waiter = callback;
                waiterEpoch = epoch;
            }
        } finally {
            lock.unlock();
        }
        if (now) {
            callback.run();
        }
    }

    /** Disarms the callback {@link #onNewer} armed, if one is armed. */
    public void clearNewer() {
        lock.lock();
        try {
            waiter = null;
        } finally {
            lock.unlock();
        }
    }

    /** Whether this mirror has stopped publishing, because it was closed or its anchor has gone. */
    public boolean isEnded() {
        lock.lock();
        try {
            return ended;
        } finally {
            lock.unlock();
        }
    }

    /** Why this mirror ended, when something other than {@link #close()} ended it. */
    public Optional<Throwable> cause() {
        lock.lock();
        try {
            return Optional.ofNullable(cause);
        } finally {
            lock.unlock();
        }
    }

    /** Stops listening and detaches the control client. The last view stays readable. Idempotent. */
    @Override
    public void close() {
        closing = true;
        listener.interrupt();
        try {
            listener.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        detach();
        end(null);
    }

    private void listen() {
        try {
            while (!closing) {
                EventSubscription<ControlEvent> heard = Objects.requireNonNull(events);
                Optional<Delivery<ControlEvent>> step = refreshEvery.isZero() ? heard.next() : heard.next(refreshEvery);
                if (closing) {
                    return;
                }
                if (step.isEmpty() && heard.isClosed()) {
                    reattach();
                    continue;
                }
                // What else is already waiting is covered by the same rebuild.
                while (heard.poll().isPresent()) {}
                publish(server.snapshot());
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            if (!closing) {
                detach();
                end(failure);
            }
        }
    }

    /** The control client ended; tmux has forgotten what it announced, so start from a new capture. */
    private void reattach() {
        detach();
        Session session = server.session(anchor)
                .orElseThrow(() -> new TargetGoneException(
                        "session " + anchor.value() + ", which this mirror listened through, has gone"));
        attach(session);
        publish(server.snapshot());
    }

    private void attach(Session session) {
        ControlClient attached = server.control(session);
        try {
            events = attached.subscribeEvents(EVENTS);
        } catch (RuntimeException failure) {
            attached.close();
            throw failure;
        }
        control = attached;
    }

    private void detach() {
        EventSubscription<ControlEvent> heard = events;
        if (heard != null) {
            heard.close();
        }
        ControlClient attached = control;
        if (attached != null) {
            attached.close();
        }
    }

    private void publish(ServerSnapshot fresh) {
        @Nullable Runnable wake = null;
        lock.lock();
        try {
            if (ended || sameState(view.snapshot(), fresh)) {
                return;
            }
            view = new View(view.epoch() + 1, fresh);
            published.signalAll();
            if (waiter != null && view.epoch() > waiterEpoch) {
                wake = waiter;
                waiter = null;
            }
        } finally {
            lock.unlock();
        }
        if (wake != null) {
            wake.run();
        }
    }

    private void end(@Nullable Throwable failure) {
        @Nullable Runnable wake;
        lock.lock();
        try {
            if (ended) {
                return;
            }
            ended = true;
            cause = failure;
            published.signalAll();
            wake = waiter;
            waiter = null;
        } finally {
            lock.unlock();
        }
        if (wake != null) {
            wake.run();
        }
    }

    /** The same server state, whenever it was captured. */
    private static boolean sameState(ServerSnapshot before, ServerSnapshot after) {
        return before.sessions().equals(after.sessions())
                && before.windows().equals(after.windows())
                && before.panes().equals(after.panes())
                && before.clients().equals(after.clients())
                && before.serverPid().equals(after.serverPid())
                && before.serverStartTime().equals(after.serverStartTime());
    }

    private static long nanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
