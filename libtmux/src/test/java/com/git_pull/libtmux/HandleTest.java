package com.git_pull.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.format.RowFormat;
import com.git_pull.libtmux.transport.CommandRequest;
import com.git_pull.libtmux.transport.CommandResult;
import com.git_pull.libtmux.transport.TmuxTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What a handle is: a stable identity plus the state one capture saw.
 *
 * <p>Identity is what survives a rename and a resize; everything a user can change is state. A
 * handle whose equality included its name would stop matching itself the moment tmux renamed
 * something, and a set of handles would silently grow duplicates.
 */
final class HandleTest {

    private static final String SEP = RowFormat.of("x").separator();

    // ------------------------------------------------------------------------------- identity

    @Test
    void aSessionIsItsServerAndItsIdNotItsName() {
        try (Server server = canned()) {
            Session first = server.sessions().get(0);
            Session again = server.sessions().get(0);

            assertEquals(first, again);
            assertEquals(first.hashCode(), again.hashCode());
            assertEquals(1, Set.copyOf(List.of(first, again)).size(), "one session is one key");
        }
    }

    @Test
    void aRenameDoesNotMakeADifferentSession() {
        try (Server before = canned("alpha");
                Server after = canned("renamed")) {
            Session original = before.sessions().get(0);
            Session renamed = after.sessions().get(0);

            assertEquals("alpha", original.name());
            assertEquals("renamed", renamed.name());
            assertEquals(original, renamed, "the name is state; the id is identity");
        }
    }

    @Test
    void aWindowIsItsWinlinkBecauseRenumberingMovesIt() {
        try (Server server = canned()) {
            Window inAlpha = server.sessions().get(0).windows().get(0);
            Window inBeta = server.sessions().get(1).windows().get(0);

            assertEquals(inAlpha.id(), inBeta.id(), "one underlying window, linked into two sessions");
            assertNotEquals(inAlpha, inBeta, "two positions are two winlinks");
            assertEquals(2, Set.copyOf(List.of(inAlpha, inBeta)).size());
        }
    }

    @Test
    void aPaneIsItsServerAndItsId() {
        try (Server server = canned()) {
            List<Pane> panes = server.sessions().get(0).windows().get(0).panes();

            assertEquals(panes.get(0), panes.get(0));
            assertNotEquals(panes.get(0), panes.get(1));
            assertEquals(2, Set.copyOf(panes).size());
        }
    }

    @Test
    void aClientIsItsNameNotWhatItIsAttachedTo() {
        try (Server server = canned()) {
            Client client = server.clients().get(0);

            assertEquals("/dev/pts/3", client.name());
            assertTrue(client.session().isPresent());
        }
    }

    @Test
    void handlesFromDifferentServersAreNeverEqual() {
        try (Server one = canned();
                Server two = canned(ServerEndpoint.namedSocket("elsewhere"))) {
            assertNotEquals(
                    one.sessions().get(0),
                    two.sessions().get(0),
                    "the same session id on another server is another session");
        }
    }

    // ------------------------------------------------------------------------------ traversal

    @Test
    void traversingAHandleAsksTmuxNothing() {
        CountingTransport transport = new CountingTransport("alpha");
        try (Server server = Server.using(config(ServerEndpoint.namedSocket("fixture")), transport)) {
            List<Session> sessions = server.sessions();
            int afterCapture = transport.calls.get();

            List<Window> windows = sessions.get(0).windows();
            List<Pane> panes = windows.get(0).panes();
            Window parent = panes.get(0).window();
            Session grandparent = parent.session();

            assertEquals(afterCapture, transport.calls.get(), "traversal must be a read of what was captured");
            assertEquals(windows.get(0), parent, "walking down then up returns the same winlink");
            assertEquals(sessions.get(0), grandparent);
        }
    }

    @Test
    void aHandleReachesOnlyItsOwnChildren() {
        try (Server server = canned()) {
            Window inAlpha = server.sessions().get(0).windows().get(0);
            Window inBeta = server.sessions().get(1).windows().get(0);

            assertEquals(
                    List.of(new PaneId("%1"), new PaneId("%2")),
                    inAlpha.panes().stream().map(Pane::id).toList());
            assertEquals(
                    List.of(new PaneId("%3")),
                    inBeta.panes().stream().map(Pane::id).toList());
        }
    }

    @Test
    void everyHandleListIsUnmodifiable() {
        try (Server server = canned()) {
            Session session = server.sessions().get(0);

            org.junit.jupiter.api.Assertions.assertThrows(
                    UnsupportedOperationException.class, () -> session.windows().clear());
        }
    }

    @Test
    void aHandleKnowsWhichServerItCameFrom() {
        try (Server server = canned()) {
            assertSame(server, server.sessions().get(0).server());
        }
    }

    // ------------------------------------------------------------------------------- fixtures

    private static ServerConfig config(ServerEndpoint endpoint) {
        return ServerConfig.builder().endpoint(endpoint).build();
    }

    private static Server canned() {
        return canned("alpha");
    }

    private static Server canned(String firstSessionName) {
        return Server.using(config(ServerEndpoint.namedSocket("fixture")), new CountingTransport(firstSessionName));
    }

    private static Server canned(ServerEndpoint endpoint) {
        return Server.using(config(endpoint), new CountingTransport("alpha"));
    }

    /**
     * Answers the four listings from fixed rows, and counts what it was asked. A window linked into
     * two sessions is the shape that matters, so it is what the rows describe.
     */
    private static final class CountingTransport implements TmuxTransport {

        private final AtomicInteger calls = new AtomicInteger();
        private final String firstSessionName;

        CountingTransport(String firstSessionName) {
            this.firstSessionName = firstSessionName;
        }

        @Override
        public CommandResult execute(CommandRequest request) {
            calls.incrementAndGet();
            String command = request.argv().get(0);
            return new CommandResult(0, rows(command), List.of());
        }

        private List<String> rows(String command) {
            List<String> rows = new ArrayList<>();
            switch (command) {
                case "list-sessions" -> {
                    rows.add(row("$0", firstSessionName, "1", "1"));
                    rows.add(row("$1", "beta", "0", "1"));
                }
                case "list-windows" -> {
                    rows.add(row("$0", "@7", "0", "editor", "1", "2", "1", "80", "24", "layout"));
                    rows.add(row("$1", "@7", "3", "editor", "0", "2", "1", "80", "24", "layout"));
                }
                case "list-panes" -> {
                    rows.add(row(
                            "$0", "@7", "0", "%1", "0", "1", "nvim", "80", "24", "t", "/tmp", "11", "1", "1", "1",
                            "1"));
                    rows.add(row(
                            "$0", "@7", "0", "%2", "1", "0", "zsh", "80", "24", "t", "/tmp", "12", "1", "1", "1", "1"));
                    rows.add(row(
                            "$1", "@7", "3", "%3", "0", "1", "nvim", "80", "24", "t", "/tmp", "13", "1", "1", "1",
                            "1"));
                }
                case "list-clients" -> rows.add(row("/dev/pts/3", "$0"));
                // Reported as 3.6 so the snapshot uses the format without pane_floating_flag,
                // which is what these fixed rows describe.
                case "display-message" -> rows.add("3.6");
                default -> {
                    // Any other command is an operation, not a listing.
                }
            }
            return rows;
        }

        private static String row(String... fields) {
            return String.join(SEP, fields);
        }

        @Override
        public void close() {}
    }
}
