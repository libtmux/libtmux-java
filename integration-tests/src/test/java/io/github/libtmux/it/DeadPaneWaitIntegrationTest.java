package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.TextOutcome;
import io.github.libtmux.TextWait;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A wait for text whose pane, or whose server, goes away underneath it.
 *
 * <p>Each case runs with and without a second control client stopped on the socket. tmux keeps the
 * daemon until every client leaves, so an answer that came from counting clients or from a grace
 * timer would differ between the two; the answer here is the server's own, now.
 */
@ExtendWith(TmuxExtension.class)
final class DeadPaneWaitIntegrationTest {

    private static final String PRINTED = "printed-before-the-end";

    private static Pane victim(Server server) {
        return server.newSession(session ->
                        session.named("victim").running("sh", "-c", "sleep 1; echo " + PRINTED + "; sleep 300"))
                .windows()
                .get(0)
                .panes()
                .get(0);
    }

    /**
     * Counts the pane reads the wait under test completes, so a test can tell that the wait has read
     * the output instead of guessing how long that takes on a slow runner.
     */
    private static final class Reads implements AutoCloseable {
        private final ProcessTransport processes = new ProcessTransport();
        private final AtomicInteger completed = new AtomicInteger();

        Pane over(Server server, Pane pane) {
            TmuxTransport counting = new TmuxTransport() {
                @Override
                public CommandResult execute(CommandRequest request) {
                    CommandResult result = processes.execute(request);
                    if (request.commands().stream()
                            .anyMatch(command -> command.stream().anyMatch(word -> word.contains("capture-pane")))) {
                        completed.incrementAndGet();
                    }
                    return result;
                }

                @Override
                public void close() {}
            };
            return Server.using(server.config(), counting).pane(pane.id()).orElseThrow();
        }

        @Override
        public void close() {
            processes.close();
        }
    }

    /** Waits for the output to be on the pane, then for the wait to have read the pane after that. */
    private static void awaitPrinted(Pane pane, Reads reads) throws Exception {
        assertTrue(Await.until(() -> pane.capture().stream().anyMatch(line -> line.contains(PRINTED))), "no output");
        int seen = reads.completed.get();
        assertTrue(Await.until(() -> reads.completed.get() >= seen + 3), "the wait did not read the pane again");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPaneKilledMidWaitIsAFailedWaitWithTheOutputSoFar(boolean withStoppedClient, Server server) throws Exception {
        server.newSession(session -> session.named("outlives"));
        Pane pane = victim(server);
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try (StoppedClient _ = StoppedClient.attach(server, "outlives", withStoppedClient);
                Reads reads = new Reads()) {
            Pane watched = reads.over(server, pane);
            Future<TextWait> waiting = waiter.submit(() -> watched.watchText("never-printed", Duration.ofSeconds(60)));
            awaitPrinted(pane, reads);
            long killed = System.nanoTime();
            pane.kill();

            TextWait wait = waiting.get(15, TimeUnit.SECONDS);

            assertEquals(TextOutcome.PANE_GONE, wait.outcome());
            assertTrue(wait.output().contains(PRINTED), "output so far: " + wait.output());
            assertTrue(System.nanoTime() - killed < TimeUnit.SECONDS.toNanos(10), "a timer decided this");
        } finally {
            waiter.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void awaitTextReportsAKilledPaneAsAnOutcomeToo(boolean withStoppedClient, Server server) throws Exception {
        server.newSession(session -> session.named("outlives"));
        Pane pane = victim(server);
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try (StoppedClient _ = StoppedClient.attach(server, "outlives", withStoppedClient);
                Reads reads = new Reads()) {
            Pane watched = reads.over(server, pane);
            Future<TextOutcome> waiting =
                    waiter.submit(() -> watched.awaitText("never-printed", Duration.ofSeconds(60)));
            awaitPrinted(pane, reads);
            pane.kill();

            assertEquals(TextOutcome.PANE_GONE, waiting.get(15, TimeUnit.SECONDS));
        } finally {
            waiter.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aServerKilledMidWaitIsServerGoneWithTheOutputSoFar(boolean withStoppedClient, Server server) throws Exception {
        server.newSession(session -> session.named("outlives"));
        Pane pane = victim(server);
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try (StoppedClient _ = StoppedClient.attach(server, "outlives", withStoppedClient);
                Reads reads = new Reads()) {
            Pane watched = reads.over(server, pane);
            Future<TextWait> waiting = waiter.submit(() -> watched.watchText("never-printed", Duration.ofSeconds(60)));
            awaitPrinted(pane, reads);
            long killed = System.nanoTime();
            server.cmd("kill-server");

            TextWait wait = waiting.get(15, TimeUnit.SECONDS);

            assertEquals(TextOutcome.SERVER_GONE, wait.outcome());
            assertTrue(wait.output().contains(PRINTED), "output so far: " + wait.output());
            assertTrue(System.nanoTime() - killed < TimeUnit.SECONDS.toNanos(10), "a timer decided this");
        } finally {
            waiter.shutdownNow();
        }
    }

    /** A predicate the caller wrote has no failed-wait answer for a pane, so a gone pane still raises. */
    @org.junit.jupiter.api.Test
    void aCallersOwnConditionStillRaisesForAGonePane(Server server) throws Exception {
        server.newSession(session -> session.named("outlives"));
        Pane pane = victim(server);
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try {
            Future<Object> waiting = waiter.submit(
                    () -> pane.await(fresh -> fresh.capture().contains("never-printed"), Duration.ofSeconds(60)));
            Thread.sleep(500);
            pane.kill();

            var failure = assertThrows(
                    java.util.concurrent.ExecutionException.class, () -> waiting.get(15, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof LibTmuxException, String.valueOf(failure.getCause()));
        } finally {
            waiter.shutdownNow();
        }
    }
}
