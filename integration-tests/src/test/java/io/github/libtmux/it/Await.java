package io.github.libtmux.it;

import io.github.libtmux.Pane;
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

    /** Whether the pane showed the text within the budget. */
    static boolean output(Pane pane, String expected) {
        return until(() -> pane.capture().stream().anyMatch(line -> line.contains(expected)));
    }
}
