package io.github.libtmux;

import java.util.HashMap;
import java.util.Map;

/**
 * One writer at a time for a pane's keyboard, paste, and shell run.
 *
 * <p>Those three share one screen. {@link #hold} throws if another thread already has the pane.
 * The same thread may hold it again; the pane is free when the outer hold closes. {@link
 * Pane#sendKeys}, {@link Pane#paste}, and {@link Pane#run} take a hold for the call and release it
 * when they return. A command still running after {@code run} returns is not held unless the
 * caller kept {@link #hold} or {@link #holdInterruptible} around that call.
 *
 * <p>{@link #holdInterruptible} is that longer hold. Another thread may {@link #enterInterrupt} it
 * to send a stop, and that entry does not take the pane. Closing the run's lease still releases
 * it, including from the thread that notices the command has finished.
 */
public final class PaneInput {

    private static final Map<Key, Hold> HELD = new HashMap<>();

    private PaneInput() {}

    /** Excludes other threads from this pane until the lease closes. */
    public static Lease hold(Pane pane) {
        return hold(pane.server().identity(), pane.id());
    }

    static Lease hold(ServerIdentity identity, PaneId pane) {
        return acquire(identity, pane, false, false);
    }

    /**
     * Holds the pane for a command that another thread may have to stop.
     *
     * <p>The stop uses {@link #enterInterrupt}. Any other attempt to {@link #hold} still fails.
     */
    public static Lease holdInterruptible(Pane pane) {
        return holdInterruptible(pane.server().identity(), pane.id());
    }

    static Lease holdInterruptible(ServerIdentity identity, PaneId pane) {
        return acquire(identity, pane, true, false);
    }

    /**
     * Enters a pane {@link #holdInterruptible} is holding, without taking it.
     *
     * <p>Closing this lease leaves the run's hold in place. A pane that is free, or held by
     * {@link #hold}, refuses the entry.
     */
    public static Lease enterInterrupt(Pane pane) {
        return enterInterrupt(pane.server().identity(), pane.id());
    }

    static Lease enterInterrupt(ServerIdentity identity, PaneId pane) {
        return acquire(identity, pane, false, true);
    }

    private static Lease acquire(ServerIdentity identity, PaneId pane, boolean interruptible, boolean guest) {
        Key key = new Key(identity, pane);
        Thread self = Thread.currentThread();
        synchronized (HELD) {
            Hold current = HELD.get(key);
            if (guest) {
                if (current == null || !current.interruptible) {
                    throw new IllegalStateException("pane " + pane + " input is not open to an interrupt");
                }
                if (current.thread.equals(self)) {
                    current.depth++;
                    return new Lease(key, self, false);
                }
                current.guests.merge(self, 1, Integer::sum);
                return new Lease(key, self, true);
            }
            if (current == null) {
                HELD.put(key, new Hold(self, interruptible));
            } else if (current.thread.equals(self)) {
                current.depth++;
            } else if (current.guests.getOrDefault(self, 0) > 0) {
                current.guests.merge(self, 1, Integer::sum);
                return new Lease(key, self, true);
            } else {
                throw new IllegalStateException("pane " + pane + " input is owned by another thread");
            }
        }
        return new Lease(key, self, false);
    }

    /** Releases one matching hold. Closing twice releases once. Any thread may close it. */
    public static final class Lease implements AutoCloseable {

        private final Key key;
        private final Thread depthThread;
        private final boolean guest;
        private boolean closed;

        private Lease(Key key, Thread depthThread, boolean guest) {
            this.key = key;
            this.depthThread = depthThread;
            this.guest = guest;
        }

        @Override
        public void close() {
            synchronized (HELD) {
                if (closed) {
                    return;
                }
                closed = true;
                Hold current = HELD.get(key);
                if (current == null) {
                    return;
                }
                if (guest) {
                    int depth = current.guests.getOrDefault(depthThread, 0);
                    if (depth <= 1) {
                        current.guests.remove(depthThread);
                    } else {
                        current.guests.put(depthThread, depth - 1);
                    }
                    return;
                }
                current.depth--;
                if (current.depth == 0) {
                    HELD.remove(key);
                }
            }
        }
    }

    private record Key(ServerIdentity identity, PaneId pane) {}

    private static final class Hold {
        private final Thread thread;
        private final boolean interruptible;
        private final Map<Thread, Integer> guests = new HashMap<>();
        private int depth = 1;

        private Hold(Thread thread, boolean interruptible) {
            this.thread = thread;
            this.interruptible = interruptible;
        }
    }
}
