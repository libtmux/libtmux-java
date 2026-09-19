package io.github.libtmux.it;

import io.github.libtmux.Pane;
import io.github.libtmux.TextOutcome;
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
     * Whether the pane displays the text within the budget, whoever put it there.
     *
     * <p>The counterpart to {@link #output}, for a test that has to confirm its own typing arrived.
     * A wait discounts what this library typed, on purpose — that is the caller's question coming
     * back, not the pane's answer — so only a capture can say the characters reached the pane.
     */
    static boolean shown(Pane pane, String expected) {
        return until(() -> pane.capture().stream().anyMatch(line -> line.contains(expected)));
    }

    /**
     * Whether the pane showed the text within the budget.
     *
     * <p>The library's own wait, not another copy of one. It reports why it ended; this suite only
     * ever needs whether the text arrived, so the reason is collapsed here rather than at every
     * call site. Text already on screen counts as arrived: a caller asking this question does not
     * care whether it beat the wait there.
     */
    static boolean output(Pane pane, String expected) {
        try {
            TextOutcome outcome = pane.awaitText(expected, Duration.ofMillis(ATTEMPTS * INTERVAL_MILLIS));
            return outcome == TextOutcome.APPEARED || outcome == TextOutcome.PRESENT_AT_ENTRY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
