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
 * caller kept {@link #hold} around that call.
 */
public final class PaneInput {

    private static final Map<Key, Hold> HELD = new HashMap<>();

    private PaneInput() {}

    /** Excludes other threads from this pane until the lease closes. */
    public static Lease hold(Pane pane) {
        return hold(pane.server().identity(), pane.id());
    }

    static Lease hold(ServerIdentity identity, PaneId pane) {
        Key key = new Key(identity, pane);
        synchronized (HELD) {
            Hold current = HELD.get(key);
            if (current == null) {
                HELD.put(key, new Hold(Thread.currentThread()));
            } else if (current.thread.equals(Thread.currentThread())) {
                current.depth++;
            } else {
                throw new IllegalStateException("pane " + pane + " input is owned by another thread");
            }
        }
        return new Lease(key);
    }

    /** Releases one matching {@link #hold}. Closing twice releases once. */
    public static final class Lease implements AutoCloseable {

        private final Key key;
        private boolean closed;

        private Lease(Key key) {
            this.key = key;
        }

        @Override
        public void close() {
            synchronized (HELD) {
                if (closed) {
                    return;
                }
                closed = true;
                Hold current = HELD.get(key);
                if (current == null || !current.thread.equals(Thread.currentThread())) {
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
        private int depth = 1;

        private Hold(Thread thread) {
            this.thread = thread;
        }
    }
}
