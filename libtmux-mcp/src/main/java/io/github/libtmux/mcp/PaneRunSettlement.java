package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import java.time.Duration;

/** Retains a dispatched run's pane ownership until its outcome is no longer uncertain. */
final class PaneRunSettlement {

    private static final Duration FIRST_PROBE = Duration.ofMillis(100);
    private static final Duration MAX_PROBE = Duration.ofSeconds(5);

    private PaneRunSettlement() {}

    static void retain(PaneInputReservations.Lease lease, Pane pane, String channel, String endMarker) {
        Thread.ofVirtual().name("libtmux-run-settlement").start(() -> settle(lease, pane, channel, endMarker));
    }

    private static void settle(PaneInputReservations.Lease lease, Pane pane, String channel, String endMarker) {
        Duration delay = FIRST_PROBE;
        while (true) {
            boolean waited = true;
            try {
                pane.server().channel(channel).await(delay);
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                waited = false;
            }

            PaneInputCohort.Presence presence = lease.presence(pane);
            if (presence == PaneInputCohort.Presence.GONE
                    || (presence == PaneInputCohort.Presence.PRESENT && hasStatusMarker(pane, endMarker))) {
                lease.close();
                return;
            }
            if (!waited && !pause(delay)) {
                return;
            }
            delay = Duration.ofMillis(Math.min(MAX_PROBE.toMillis(), delay.toMillis() * 2));
        }
    }

    private static boolean hasStatusMarker(Pane pane, String endMarker) {
        try {
            for (String line : pane.capture()) {
                if (RunningCommands.parseStatus(line.trim(), endMarker) != null) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // Ambiguous observation retains ownership for the next probe.
        }
        return false;
    }

    private static boolean pause(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
