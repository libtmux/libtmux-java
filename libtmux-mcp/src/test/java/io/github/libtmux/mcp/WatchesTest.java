package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Being told what changed, rather than a model asking whether anything did.
 *
 * <p>tmux does the comparing on its own timer and pushes only differences, so a client that
 * subscribes spends nothing while the server is idle. What is tested here is that the right resource
 * is named — a notification about the wrong URI is worse than none, because a client acts on it.
 */
@ExtendWith(TmuxExtension.class)
final class WatchesTest {

    /** Records what would have gone out over the protocol. */
    private static final class Heard implements Watches.Notifier {

        private final List<String> updated = new CopyOnWriteArrayList<>();

        @Override
        public void updated(String uri) {
            updated.add(uri);
        }

        void clear() {
            updated.clear();
        }
    }

    @Test
    void aWindowAppearingTellsTheClientTheListingIsStale(Server server) throws Exception {
        Connection connection = Connection.to(server, Safety.MUTATING);
        Heard heard = new Heard();

        try (Watches watching = Watches.start(connection, heard)) {
            assertTrue(watching.isAlive(), "the control client stayed up");
            server.sessions().get(0).newWindow("appeared");

            assertTrue(await(() -> heard.updated.contains("tmux://sessions")), heard.updated.toString());
            assertTrue(heard.updated.contains("tmux://panes"));
            assertTrue(heard.updated.contains("tmux://server"));
            assertTrue(heard.updated.contains("tmux://sessions/libtmux"));
            String pane = server.windows().stream()
                    .filter(window -> window.name().equals("appeared"))
                    .findFirst()
                    .orElseThrow()
                    .panes()
                    .getFirst()
                    .id()
                    .value();
            assertTrue(heard.updated.contains(Resources.paneUri(new PaneId(pane))));
            assertTrue(heard.updated.contains(Resources.paneContentUri(new PaneId(pane))));
        }
    }

    /** Output in a pane invalidates that pane's content, and names the pane it happened in. */
    @Test
    void outputInAPaneNamesThatPanesContentAsStale(Server server) throws Exception {
        Connection connection = Connection.to(server, Safety.MUTATING);
        String pane = server.panes().get(0).id().value();
        Heard heard = new Heard();

        try (Watches watching = Watches.start(connection, heard)) {
            assertTrue(watching.isAlive());
            server.run(List.of("send-keys", "-l", "-t", pane, "echo watched-output"));
            server.run(List.of("send-keys", "-t", pane, "Enter"));

            assertTrue(
                    await(() -> heard.updated.contains(Resources.paneContentUri(new PaneId(pane)))),
                    "the pane that produced output is the one named: " + heard.updated);
        }
    }

    @Test
    void anInPlaceRedrawInvalidatesContentEvenWhenTheCursorDoesNotMove(Server server) throws Exception {
        Connection connection = Connection.to(server, Safety.MUTATING);
        Pane pane = server.panes().getFirst();
        String content = Resources.paneContentUri(pane.id());
        Heard heard = new Heard();

        try (Watches watching = Watches.start(connection, heard)) {
            assertTrue(watching.isAlive());
            assertTrue(await(() -> heard.updated.contains(content)), "the initial subscription never settled");
            heard.clear();
            pane.sendLine("printf first; sleep 4; printf '\\rother'; sleep 2");
            assertTrue(await(() -> heard.updated.contains(content)), "the first output was not observed");
            Thread.sleep(1_200);
            heard.clear();

            assertTrue(await(() -> heard.updated.contains(content)), "the in-place redraw was missed");
        }
    }

    @Test
    void outputInASecondSessionIsWatched(Server server) throws Exception {
        Pane second =
                server.newSession("watched-second").windows().getFirst().panes().getFirst();
        String content = Resources.paneContentUri(second.id());
        Heard heard = new Heard();

        try (Watches watching = Watches.start(Connection.to(server, Safety.MUTATING), heard)) {
            assertTrue(watching.isAlive());
            second.sendLine("echo second-session-output");

            assertTrue(await(() -> heard.updated.contains(content)), "the second session was not covered");
        }
    }

    @Test
    void concurrentProducersNeverCallTheProtocolNotifierConcurrently(Server server) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondReturned = new CountDownLatch(1);
        AtomicBoolean notifying = new AtomicBoolean();
        AtomicBoolean concurrent = new AtomicBoolean();
        Watches.Notifier slow = uri -> {
            if (!notifying.compareAndSet(false, true)) {
                concurrent.set(true);
            }
            entered.countDown();
            await(release);
            notifying.set(false);
        };

        try (Watches watching = Watches.start(Connection.to(server, Safety.MUTATING), slow)) {
            Thread first = Thread.ofVirtual().start(() -> watching.output(new PaneId("%900")));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the first notification never started");
            Thread second = Thread.ofVirtual().start(() -> {
                watching.output(new PaneId("%901"));
                secondReturned.countDown();
            });

            assertTrue(secondReturned.await(2, TimeUnit.SECONDS), "a slow client blocked a producer");
            assertFalse(concurrent.get(), "the protocol notifier was entered concurrently");
            release.countDown();
            first.join();
            second.join();
        } finally {
            release.countDown();
        }
    }

    @Test
    void outageRetriesBackOffToACapAndRealActivityResetsThem() {
        Watches.RetryBackoff backoff = new Watches.RetryBackoff();

        assertEquals(Duration.ofMillis(250), backoff.delay());
        backoff.failedRetry();
        assertEquals(Duration.ofMillis(500), backoff.delay());
        for (int attempt = 0; attempt < 20; attempt++) {
            backoff.failedRetry();
        }
        assertEquals(Duration.ofSeconds(8), backoff.delay());
        backoff.reset();
        assertEquals(Duration.ofMillis(250), backoff.delay());
    }

    @Test
    void aNewPanesFirstInvalidationComesAfterItsWatcherIsAttached(Server server) throws Exception {
        CountDownLatch inspect = new CountDownLatch(1);
        CountDownLatch contentAnnounced = new CountDownLatch(1);
        AtomicReference<String> content = new AtomicReference<>();
        AtomicReference<io.github.libtmux.SessionId> session = new AtomicReference<>();
        AtomicBoolean attachedAtAnnouncement = new AtomicBoolean();
        Watches.Notifier notifier = uri -> {
            await(inspect);
            if (uri.equals(content.get())) {
                attachedAtAnnouncement.set(server.snapshot().clients().stream()
                        .anyMatch(client ->
                                client.session().filter(session.get()::equals).isPresent()));
                contentAnnounced.countDown();
            }
        };

        try (Watches watching = Watches.start(Connection.to(server, Safety.MUTATING), notifier)) {
            assertTrue(watching.isAlive());
            var addedSession = server.newSession("attached-before-announced");
            Pane added = addedSession.windows().getFirst().panes().getFirst();
            session.set(addedSession.id());
            content.set(Resources.paneContentUri(added.id()));
            inspect.countDown();

            assertTrue(contentAnnounced.await(10, TimeUnit.SECONDS), "the new pane was not invalidated");
            assertTrue(attachedAtAnnouncement.get(), "a client could refresh before output watching was active");
        } finally {
            inspect.countDown();
        }
    }

    @Test
    void watchingRecoversAfterTheTmuxServerRestarts(Server server) throws Exception {
        Heard heard = new Heard();

        try (Watches watching = Watches.start(Connection.to(server, Safety.MUTATING), heard)) {
            server.killServer();
            // tmux answers a socket whose server is still exiting with "server exited unexpectedly",
            // and leaves the socket file behind either way, so the replacement is retried rather
            // than assumed. What the watcher does once one exists is the subject here.
            assertTrue(await(() -> restarted(server)), "no replacement server could be started");
            Pane reborn =
                    server.sessions().getFirst().windows().getFirst().panes().getFirst();
            String content = Resources.paneContentUri(reborn.id());
            reborn.sendLine("echo after-restart");

            assertTrue(await(watching::isAlive), "the watcher never reattached");
            assertTrue(await(() -> heard.updated.contains(content)), "output after restart was not observed");
        }
    }

    /**
     * Watching attaches a client, and an attached client is exactly what "is anybody looking at this"
     * is answered with. This server's own watcher must not be mistaken for a person.
     */
    @Test
    void theWatchersOwnClientIsNotReportedAsSomebodyWatching(Server server) throws Exception {
        Connection connection = Connection.to(server, Safety.MUTATING);

        try (Watches watching = Watches.start(connection, new Heard())) {
            assertTrue(watching.isAlive());
            assertTrue(await(() -> !server.clients().isEmpty()), "the control client really did attach");

            Listings.Clients clients = Listings.clients(new Call(connection, java.util.Map.of(), Call.Progress.SILENT));
            Listings.Sessions sessions = Listings.sessions(connection);

            assertEquals(0, clients.count(), "our own watcher is not a person watching");
            assertTrue(String.valueOf(clients.note()).contains("no person is watching"));
            assertFalse(sessions.sessions().getFirst().attached(), "our own watcher did not attach a person");
        }
    }

    /** A connection nothing is watching hides nothing, so a real client still counts. */
    @Test
    void withoutAWatcherEveryAttachedClientIsReported(Server server) {
        Connection connection =
                new Connection(server, Caller.nowhere(), Safety.MUTATING, ConcurrentHashMap.newKeySet());

        Listings.Clients clients = Listings.clients(new Call(connection, java.util.Map.of(), Call.Progress.SILENT));

        assertEquals(server.clients().size(), clients.count());
    }

    @Test
    void explicitlyRequestedWatchingFailsLoudlyWhenNothingCanBeAttached(Server server) {
        Connection connection = Connection.to(server, Safety.MUTATING);
        server.killSession(server.sessions().get(0).name());

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> Watches.start(connection, new Heard()));

        assertTrue(String.valueOf(refused.getMessage()).contains("session"), refused.getMessage());
    }

    private static boolean restarted(Server server) {
        try {
            server.newSession("reborn");
            return true;
        } catch (RuntimeException stillExiting) {
            return false;
        }
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        // tmux checks a subscription about once a second, so this has to outlast that.
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
