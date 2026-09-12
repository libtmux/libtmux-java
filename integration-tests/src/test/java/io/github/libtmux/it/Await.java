package io.github.libtmux.it;

import io.github.libtmux.Pane;
import io.github.libtmux.WakeReason;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Waits for tmux, and for the shells running inside it, to catch up.
 *
 * <p>A pane's output arrives when its shell is scheduled, which this suite does not control and a
 * matrix lane makes slower: every release runs the whole suite on the same machine. The budget is
 * therefore generous rather than tight — it is only ever spent when something is already wrong, and
 * a bound too small for a loaded machine reports the load rather than the library.
 */
final class Await {

    private static final int ATTEMPTS = 300;
    private static final long INTERVAL_MILLIS = 50;

    private Await() {}

    /** Whether the condition held within the budget. */
    static boolean until(BooleanSupplier condition) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Whether the pane showed the text within the budget.
     *
     * <p>The library's own wait, not another copy of one. It reports why it ended; this suite only
     * ever needs whether the text arrived, so the reason is collapsed here rather than at every
     * call site.
     */
    static boolean output(Pane pane, String expected) {
        try {
            return pane.awaitText(expected, Duration.ofMillis(ATTEMPTS * INTERVAL_MILLIS)) == WakeReason.SIGNALLED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
