package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.WakeReason;
import io.github.libtmux.junit5.TmuxExtension;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Waiting, and why every wait here answers with a reason rather than a boolean.
 *
 * <p>tmux's own {@code wait-for} misleads a caller twice, silently: it exits successfully when the
 * server dies under the waiter, and it remembers a signal sent when nobody was waiting. The channel
 * cases pin both down.
 *
 * <p>The pane cases cover the other rung. A channel wait is exact and belongs to a caller who wrote
 * the command; polling a pane's text is the heuristic for output nobody here authored. Both report
 * the same {@link WakeReason}, because "nothing happened" and "the server went away" call for
 * opposite recovery whichever way the wait was spelled.
 */
@ExtendWith(TmuxExtension.class)
final class WaitForIntegrationTest {

    private static final Duration SHORT = Duration.ofSeconds(3);

    @Test
    void aSignalWakesAWaiter(Server server) throws Exception {
        ExecutorService signaller = Executors.newSingleThreadExecutor();
        try {
            Future<WakeReason> waiting =
                    signaller.submit(() -> server.channel("woken").await(Duration.ofSeconds(20)));
            Thread.sleep(300);
            server.channel("woken").signal();

            assertEquals(WakeReason.SIGNALLED, waiting.get(30, TimeUnit.SECONDS));
        } finally {
            signaller.shutdownNow();
        }
    }

    @Test
    void nothingSignallingIsATimeoutRatherThanAWake(Server server) {
        assertEquals(WakeReason.TIMED_OUT, server.channel("never-signalled").await(SHORT));
    }

    /**
     * tmux remembers a signal sent with nobody waiting, so a channel can arrive already satisfied —
     * possibly from an earlier run of a different program. Draining is how a caller starts clean.
     */
    @Test
    void aStaleSignalIsConsumedByDrainingRatherThanSatisfyingTheNextWait(Server server) {
        server.channel("stale").signal();

        assertTrue(server.channel("stale").drain(), "the buffered signal was there");
        assertFalse(server.channel("stale").drain(), "and only one of it");
        assertEquals(
                WakeReason.TIMED_OUT,
                server.channel("stale").await(SHORT),
                "after draining, a wait waits rather than returning on somebody else's signal");
    }

    @Test
    void anUndrainedStaleSignalWouldHaveSatisfiedTheWait(Server server) {
        server.channel("undrained").signal();

        assertEquals(
                WakeReason.SIGNALLED,
                server.channel("undrained").await(SHORT),
                "this is the trap: nothing signalled during the wait, and it woke anyway");
    }

    /**
     * The second trap. tmux exits successfully when the server dies under a waiter, so success is
     * checked against the server still being there rather than taken at face value.
     */
    @Test
    void aServerDyingUnderTheWaiterIsNotAWake(Server server) throws Exception {
        ExecutorService killer = Executors.newSingleThreadExecutor();
        try {
            Future<WakeReason> waiting =
                    killer.submit(() -> server.channel("doomed").await(Duration.ofSeconds(20)));
            Thread.sleep(500);
            server.killServer();

            assertEquals(
                    WakeReason.SERVER_GONE,
                    waiting.get(30, TimeUnit.SECONDS),
                    "tmux called this a successful wake; nothing the wait guarded can be relied on");
        } finally {
            killer.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------ pane waits

    @Test
    void aPaneWaitSeesTextThePaneProduces(Server server) throws InterruptedException {
        Pane pane = server.sessions().getFirst().windows().getFirst().panes().getFirst();

        pane.sendLine("echo waited-for-this");

        assertEquals(WakeReason.SIGNALLED, pane.awaitText("waited-for-this", SHORT));
    }

    @Test
    void aPaneWaitForTextThatNeverComesIsATimeout(Server server) throws InterruptedException {
        Pane pane = server.sessions().getFirst().windows().getFirst().panes().getFirst();

        assertEquals(WakeReason.TIMED_OUT, pane.awaitText("nothing-prints-this", Duration.ofMillis(600)));
    }

    /**
     * The condition is tested against tmux, not against the capture the handle was built from.
     *
     * <p>The title is changed through a different handle, so this one still holds the old value. A
     * wait that tested its own captured state would never see the new one and would time out.
     */
    @Test
    void aPaneWaitReadsFreshStateRatherThanTheCaptureItStartedFrom(Server server) throws InterruptedException {
        Pane stale = server.sessions().getFirst().windows().getFirst().panes().getFirst();
        var unused = stale.retitle("waited-for-title");

        assertEquals(WakeReason.SIGNALLED, stale.await(fresh -> fresh.title().equals("waited-for-title"), SHORT));
    }

    /**
     * A pane wait carries the same distinction {@code wait-for} needed, for the same reason.
     *
     * <p>"Nothing printed it" and "the server went away mid-wait" call for opposite recovery, and a
     * poll that returned a boolean would report the second as the first.
     */
    @Test
    void aServerDyingUnderAPaneWaitIsNotATimeout(Server server) throws Exception {
        Pane pane = server.sessions().getFirst().windows().getFirst().panes().getFirst();
        ExecutorService killer = Executors.newSingleThreadExecutor();
        try {
            Future<WakeReason> waiting = killer.submit(() -> pane.awaitText("never-printed", Duration.ofSeconds(20)));
            Thread.sleep(500);
            server.killServer();

            assertEquals(WakeReason.SERVER_GONE, waiting.get(30, TimeUnit.SECONDS));
        } finally {
            killer.shutdownNow();
        }
    }
}
