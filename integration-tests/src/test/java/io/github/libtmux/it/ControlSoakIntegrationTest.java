package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.TmuxTransportException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Senders racing a close, repeatedly, against a real tmux.
 *
 * <p>Every send must end with a reply or one of the two documented refusals: the client is no
 * longer usable, or the transport says how far the request got. Nothing may hang, and the server
 * must answer afterwards.
 */
@ExtendWith(TmuxExtension.class)
final class ControlSoakIntegrationTest {

    private static final int ROUNDS = 5;
    private static final int SENDERS = 8;
    private static final int SENDS = 25;

    @Test
    void sendersRacingACloseAllFinishWithAnAnswer(Server server) throws Exception {
        List<String> unexpected = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            ControlClient client = server.control(server.sessions().get(0));
            CountDownLatch replied = new CountDownLatch(SENDERS * SENDS / 4);
            ExecutorService senders = Executors.newFixedThreadPool(SENDERS);
            List<Future<?>> finished = new ArrayList<>();
            for (int sender = 0; sender < SENDERS; sender++) {
                finished.add(senders.submit(() -> {
                    for (int send = 0; send < SENDS; send++) {
                        try {
                            client.send(List.of("display-message", "-p", "soak"), Duration.ofSeconds(5));
                            replied.countDown();
                        } catch (IllegalStateException | TmuxTransportException refused) {
                            return null;
                        }
                    }
                    return null;
                }));
            }
            assertTrue(replied.await(10, TimeUnit.SECONDS), "senders stalled before the close");
            client.close();
            senders.shutdown();
            assertTrue(senders.awaitTermination(10, TimeUnit.SECONDS), "a sender hung across the close");
            for (Future<?> sender : finished) {
                try {
                    sender.get();
                } catch (java.util.concurrent.ExecutionException failure) {
                    unexpected.add(String.valueOf(failure.getCause()));
                }
            }
            assertFalse(client.isAlive(), "round " + round + " left its client running");
        }
        assertEquals(List.of(), unexpected);
        assertTrue(server.isAlive());
    }
}
