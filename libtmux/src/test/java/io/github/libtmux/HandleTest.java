package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.format.RowFormat;
import io.github.libtmux.internal.CommandStrings;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
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
                    List.of(new PaneId("%1"), new PaneId("%2")),
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

    @Test
    void operationsRejectHandlesFromAnotherServerBeforeDispatch() {
        CountingTransport localTransport = new CountingTransport("alpha");
        try (Server local = Server.using(config(ServerEndpoint.namedSocket("fixture")), localTransport);
                Server foreign = canned(ServerEndpoint.namedSocket("elsewhere"))) {
            Session localSession = local.sessions().get(0);
            Window localWindow = localSession.windows().get(0);
            Pane localPane = localWindow.panes().get(0);
            Client localClient = local.clients().get(0);
            Session foreignSession = foreign.sessions().get(0);
            Window foreignWindow = foreignSession.windows().get(0);
            Pane foreignPane = foreignWindow.panes().get(0);
            int captured = localTransport.calls.get();

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> localSession.selectWindow(foreignWindow)),
                    () -> assertThrows(IllegalArgumentException.class, () -> localWindow.linkTo(foreignSession)),
                    () -> assertThrows(IllegalArgumentException.class, () -> localWindow.moveTo(foreignSession)),
                    () -> assertThrows(IllegalArgumentException.class, () -> localPane.swapWith(foreignPane)),
                    () -> assertThrows(IllegalArgumentException.class, () -> localPane.joinTo(foreignWindow)),
                    () -> assertThrows(IllegalArgumentException.class, () -> localClient.switchTo(foreignSession)));
            assertEquals(captured, localTransport.calls.get(), "a refused handle must never reach tmux");
        }
    }

    @Test
    void selectingAWindowIsScopedToTheReceivingSession() {
        CountingTransport transport = new CountingTransport("alpha");
        try (Server server = Server.using(config(ServerEndpoint.namedSocket("fixture")), transport)) {
            Session alpha = server.sessions().get(0);
            Session beta = server.sessions().get(1);
            Window linkedIntoAlpha = alpha.windows().get(0);
            Window onlyInBeta = beta.windows().get(1);

            assertThrows(IllegalArgumentException.class, () -> alpha.selectWindow(onlyInBeta));

            alpha.selectWindow(linkedIntoAlpha);
            assertEquals(CommandStrings.stringify(List.of("select-window", "-t", "$0:0")), last(transport));
        }
    }

    @Test
    void linkSpecificOperationsKeepTheCapturedSessionAndIndex() {
        CountingTransport transport = new CountingTransport("alpha");
        try (Server server = Server.using(config(ServerEndpoint.namedSocket("fixture")), transport)) {
            Session alpha = server.sessions().get(0);
            Session beta = server.sessions().get(1);
            Window secondLink = beta.windows().get(0);

            secondLink.select();
            assertEquals(CommandStrings.stringify(List.of("select-window", "-t", "$1:3")), last(transport));

            secondLink.expand("#{window_index}");
            assertEquals(
                    CommandStrings.stringify(List.of("display-message", "-p", "-t", "$1:3", "#{window_index}")),
                    last(transport));

            secondLink.unlink();
            assertEquals(CommandStrings.stringify(List.of("unlink-window", "-t", "$1:3")), last(transport));

            secondLink.moveTo(alpha);
            assertEquals(CommandStrings.stringify(List.of("move-window", "-s", "$1:3", "-t", "$0")), last(transport));
        }
    }

    @Test
    void aHandleUsesTheVersionCapturedWithItsIdentity() {
        CountingTransport transport = new CountingTransport("alpha");
        try (Server server = Server.using(config(ServerEndpoint.namedSocket("fixture")), transport)) {
            Window window = server.windows().get(0);
            int captured = transport.calls.get();

            window.selectLayout(Layout.MAIN_HORIZONTAL_MIRRORED);

            assertEquals(captured + 1, transport.calls.get(), "the operation must not probe another server version");
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

    private static String last(CountingTransport transport) {
        List<String> argv =
                transport.requests.get(transport.requests.size() - 1).commands().get(0);
        return argv.get(argv.size() - 2);
    }

    /**
     * Answers the four listings from fixed rows, and counts what it was asked. A window linked into
     * two sessions is the shape that matters, so it is what the rows describe.
     */
    private static final class CountingTransport implements TmuxTransport {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<CommandRequest> requests = new ArrayList<>();
        private final String firstSessionName;

        CountingTransport(String firstSessionName) {
            this.firstSessionName = firstSessionName;
        }

        @Override
        public CommandResult execute(CommandRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            String command = request.commands().get(0).get(0);
            return new CommandResult(0, rows(command), List.of());
        }

        private List<String> rows(String command) {
            List<String> rows = new ArrayList<>();
            switch (command) {
                case "list-sessions" -> {
                    rows.add(row("$0", firstSessionName, "1", "1"));
                    rows.add(row("$1", "beta", "0", "2"));
                }
                case "list-windows" -> {
                    rows.add(row("$0", "@7", "0", "editor", "1", "2", "1", "80", "24", "layout"));
                    rows.add(row("$1", "@7", "3", "editor", "0", "2", "1", "80", "24", "layout"));
                    rows.add(row("$1", "@8", "4", "logs", "1", "1", "0", "80", "24", "layout"));
                }
                case "list-panes" -> {
                    rows.add(row(
                            "$0", "@7", "0", "%1", "0", "1", "nvim", "80", "24", "t", "/tmp", "11", "1", "1", "1",
                            "1"));
                    rows.add(row(
                            "$0", "@7", "0", "%2", "1", "0", "zsh", "80", "24", "t", "/tmp", "12", "1", "1", "1", "1"));
                    rows.add(row(
                            "$1", "@7", "3", "%1", "0", "1", "nvim", "80", "24", "t", "/tmp", "11", "1", "1", "1",
                            "1"));
                    rows.add(row(
                            "$1", "@7", "3", "%2", "1", "0", "zsh", "80", "24", "t", "/tmp", "12", "1", "1", "1", "1"));
                    rows.add(row(
                            "$1", "@8", "4", "%3", "0", "1", "tail", "80", "24", "t", "/tmp", "13", "1", "1", "1",
                            "1"));
                }
                case "list-clients" -> rows.add(row("/dev/pts/3", "$0"));
                // Reported as 3.6 so the snapshot uses the format without pane_floating_flag,
                // which is what these fixed rows describe.
                case "display-message" -> rows.add(row("4242", "3.6"));
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
