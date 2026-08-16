package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
        private final List<String> listChanged = new CopyOnWriteArrayList<>();

        @Override
        public void updated(String uri) {
            updated.add(uri);
        }

        @Override
        public void listChanged() {
            listChanged.add("list");
        }
    }

    @Test
    void aWindowAppearingTellsTheClientTheListingIsStale(Server server) throws Exception {
        Connection connection = Connection.to(server, Safety.MUTATING);
        Heard heard = new Heard();

        try (Watches watching = Watches.start(connection, heard).orElseThrow()) {
            assertTrue(watching.isAlive(), "the control client stayed up");
            server.sessions().get(0).newWindow("appeared");

            assertTrue(await(() -> heard.updated.contains("tmux://sessions")), heard.updated.toString());
            assertTrue(heard.updated.contains("tmux://panes"));
            assertTrue(!heard.listChanged.isEmpty(), "and the set of resources itself changed");
        }
    }

    /** Output in a pane invalidates that pane's content, and names the pane it happened in. */
    @Test
    void outputInAPaneNamesThatPanesContentAsStale(Server server) throws Exception {
        Connection connection = Connection.to(server, Safety.MUTATING);
        String pane = server.panes().get(0).id().value();
        Heard heard = new Heard();

        try (Watches watching = Watches.start(connection, heard).orElseThrow()) {
            assertTrue(watching.isAlive());
            server.run(List.of("send-keys", "-l", "-t", pane, "echo watched-output"));
            server.run(List.of("send-keys", "-t", pane, "Enter"));

            assertTrue(
                    await(() -> heard.updated.contains("tmux://panes/" + pane + "/content")),
                    "the pane that produced output is the one named: " + heard.updated);
        }
    }

    /**
     * Watching attaches a client, and an attached client is exactly what "is anybody looking at this"
     * is answered with. This server's own watcher must not be mistaken for a person.
     */
    @Test
    void theWatchersOwnClientIsNotReportedAsSomebodyWatching(Server server) throws Exception {
        Connection connection = Connection.to(server, Safety.MUTATING);

        try (Watches watching = Watches.start(connection, new Heard()).orElseThrow()) {
            assertTrue(watching.isAlive());
            assertTrue(await(() -> !server.clients().isEmpty()), "the control client really did attach");

            Listings.Clients clients = Listings.clients(new Call(connection, java.util.Map.of(), Call.Progress.SILENT));

            assertEquals(0, clients.count(), "our own watcher is not a person watching");
            assertTrue(String.valueOf(clients.note()).contains("no person is watching"));
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
}
