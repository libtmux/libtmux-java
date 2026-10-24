package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import io.github.libtmux.exception.LibTmuxException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Watches the pane of a running command for its end, and keeps what it last read.
 *
 * <p>A command's end is signalled by a trap in the pane's own shell. A pane killed under it never
 * runs that trap, so the signal never comes and the wait would run to its timeout with the pane long
 * gone. This looks at the pane while the wait blocks, and when the pane is gone it signals the same
 * channel the trap would have, so the blocked wait ends within a look. A signal sent before anything
 * waits is remembered by tmux, so there is no window in which it can be lost.
 *
 * <p>The looks run on an interval, and each is a question the server answers now: tmux's answer, not
 * the interval, decides whether the pane is gone. The last good look is also what a server killed
 * mid-wait leaves behind, since nothing can be read from it afterwards. A look reads through
 * {@code list-panes} and {@code capture-pane}, never {@code display-message}, so the user's
 * {@code after-display-message} hook does not run on every look.
 */
final class PaneWatch implements AutoCloseable {

    /** How often the pane is looked at while a command runs. */
    private static final Duration LOOK = Duration.ofMillis(250);

    private final Pane pane;
    private final Cursor from;
    private final int budget;
    private final Runnable wake;
    private final Thread thread;
    private volatile Screen.@Nullable Fresh last;
    private volatile @Nullable Ends ended;

    private PaneWatch(Pane pane, Cursor from, int budget, Runnable wake) {
        this.pane = pane;
        this.from = from;
        this.budget = budget;
        this.wake = wake;
        this.thread = Thread.ofVirtual().name("libtmux-run-watch").unstarted(this::watch);
    }

    /**
     * Starts watching.
     *
     * @param from the cursor the command started after
     * @param wake what ends the blocked wait when the pane is found gone
     */
    static PaneWatch over(Pane pane, Cursor from, int budget, Runnable wake) {
        PaneWatch watch = new PaneWatch(pane, from, budget, wake);
        watch.thread.start();
        return watch;
    }

    /** What the pane showed at the last look that worked, or null before any. */
    Screen.@Nullable Fresh last() {
        return last;
    }

    /** Why the pane stopped answering, or null while it has not. */
    @Nullable
    Ends ended() {
        return ended;
    }

    private void watch() {
        try {
            while (true) {
                Thread.sleep(LOOK);
                try {
                    last = Screen.sinceWithoutHooks(pane, from, budget);
                } catch (LibTmuxException unread) {
                    Ends why;
                    try {
                        why = Ends.of(pane, unread);
                    } catch (LibTmuxException callers) {
                        continue;
                    }
                    ended = why;
                    if (why == Ends.PANE_GONE) {
                        wake.run();
                    }
                    return;
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unwatchable) {
            // A look that cannot be made is not an end; the wait keeps its own deadline.
        }
    }

    @Override
    public void close() {
        thread.interrupt();
    }
}
