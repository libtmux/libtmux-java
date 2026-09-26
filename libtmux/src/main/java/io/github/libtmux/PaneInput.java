package io.github.libtmux;

import io.github.libtmux.catalog.Advanced;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kotlin.annotations.jvm.ReadOnly;

/**
 * One writer at a time for a pane's keyboard, paste, and shell run.
 *
 * <p>Those three share one screen. {@link #hold} throws if another thread already has the pane.
 * The same thread may hold it again; the pane is free when the outer hold closes. {@link
 * Pane#sendKeys}, {@link Pane#sendLiteral}, {@link Pane#paste}, {@link Pane#pasteBuffer}, and {@link
 * Pane#run} take a hold for the call and release it when they return. A command still running after
 * {@code run} returns is not held unless the caller kept {@link #hold} or {@link #holdInterruptible}
 * around that call. Raw commands and {@link CommandChain} steps do not take it.
 *
 * <p>{@link #holdInterruptible} is that longer hold. Another thread may {@link #enterInterrupt} it
 * to send a stop, and that entry does not take the pane. Closing the run's lease still releases
 * it, including from the thread that notices the command has finished.
 */
@Advanced
public final class PaneInput {

    private static final Map<Key, Hold> HELD = new HashMap<>();

    private PaneInput() {}

    /**
     * Excludes other threads from this pane until the lease closes. The pane is the one its capture
     * named: the same id on a server started since is another pane, and is not held.
     */
    public static Lease hold(Pane pane) {
        return hold(pane.identity(), pane.id());
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
        return holdInterruptible(pane.identity(), pane.id());
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
        return enterInterrupt(pane.identity(), pane.id());
    }

    static Lease enterInterrupt(ServerIdentity identity, PaneId pane) {
        return acquire(identity, pane, false, true);
    }

    /**
     * Reports when the current hold on this pane was acquired, so a lease nothing ever closes can
     * be found from outside the thread holding it.
     *
     * @return empty when the pane is not held
     */
    public static Optional<Instant> heldSince(Pane pane) {
        return heldSince(pane.identity(), pane.id());
    }

    static Optional<Instant> heldSince(ServerIdentity identity, PaneId pane) {
        synchronized (HELD) {
            Hold current = HELD.get(new Key(identity, pane));
            return current == null ? Optional.empty() : Optional.of(current.since);
        }
    }

    /**
     * Every pane held right now, for diagnosing a lease nothing ever closes.
     *
     * @return an immutable snapshot, one entry per held pane regardless of how many times its
     *     owning thread has re-entered it
     */
    @ReadOnly
    public static List<Held> held() {
        synchronized (HELD) {
            return HELD.entrySet().stream()
                    .map(entry -> new Held(
                            entry.getKey().identity(),
                            entry.getKey().pane(),
                            entry.getValue().since,
                            entry.getValue().thread.getName()))
                    .toList();
        }
    }

    /**
     * One pane's outstanding hold.
     *
     * @param server which server's pane this is
     * @param pane the held pane
     * @param since when the hold was acquired
     * @param holdingThread the name of the thread that holds it
     */
    public record Held(ServerIdentity server, PaneId pane, Instant since, String holdingThread) {}

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
    @Advanced
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
        private final Instant since = Instant.now();
        private final Map<Thread, Integer> guests = new HashMap<>();
        private int depth = 1;

        private Hold(Thread thread, boolean interruptible) {
            this.thread = thread;
            this.interruptible = interruptible;
        }
    }
}
