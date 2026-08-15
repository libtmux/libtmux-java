package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Waiting on a tmux channel, and the two ways tmux's own wait-for misleads a caller.
 *
 * <p>It exits successfully when the server dies under the waiter, and it remembers a signal sent
 * when nobody was waiting. Both are silent, and both are what these cases pin down.
 */
@ExtendWith(TmuxExtension.class)
final class WaitForIntegrationTest {

    private static final Duration SHORT = Duration.ofSeconds(3);

    @Test
    void aSignalWakesAWaiter(Server server) throws Exception {
        ExecutorService signaller = Executors.newSingleThreadExecutor();
        try {
            Future<WakeReason> waiting = signaller.submit(() -> server.waitFor("woken", Duration.ofSeconds(20)));
            Thread.sleep(300);
            server.signal("woken");

            assertEquals(WakeReason.SIGNALLED, waiting.get(30, TimeUnit.SECONDS));
        } finally {
            signaller.shutdownNow();
        }
    }

    @Test
    void nothingSignallingIsATimeoutRatherThanAWake(Server server) {
        assertEquals(WakeReason.TIMED_OUT, server.waitFor("never-signalled", SHORT));
    }

    /**
     * tmux remembers a signal sent with nobody waiting, so a channel can arrive already satisfied —
     * possibly from an earlier run of a different program. Draining is how a caller starts clean.
     */
    @Test
    void aStaleSignalIsConsumedByDrainingRatherThanSatisfyingTheNextWait(Server server) {
        server.signal("stale");

        assertTrue(server.drain("stale"), "the buffered signal was there");
        assertFalse(server.drain("stale"), "and only one of it");
        assertEquals(
                WakeReason.TIMED_OUT,
                server.waitFor("stale", SHORT),
                "after draining, a wait waits rather than returning on somebody else's signal");
    }

    @Test
    void anUndrainedStaleSignalWouldHaveSatisfiedTheWait(Server server) {
        server.signal("undrained");

        assertEquals(
                WakeReason.SIGNALLED,
                server.waitFor("undrained", SHORT),
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
            Future<WakeReason> waiting = killer.submit(() -> server.waitFor("doomed", Duration.ofSeconds(20)));
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
}
