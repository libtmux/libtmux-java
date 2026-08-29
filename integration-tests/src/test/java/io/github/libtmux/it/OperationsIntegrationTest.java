package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.ObjectDoesNotExist;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.junit5.TmuxExtension;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Doing things to a real tmux, then seeing them in the next capture. */
@ExtendWith(TmuxExtension.class)
final class OperationsIntegrationTest {

    private static Session session(Server server) {
        return server.sessions().get(0);
    }

    @Test
    void creatingAWindowReturnsThatExactWindow(Server server) {
        Window created = session(server).newWindow("built");

        assertEquals("built", created.name());
        List<String> names =
                session(server).windows().stream().map(Window::name).toList();
        assertEquals(2, names.size(), "the fixture window plus the one just built");
        assertTrue(names.contains("built"));
    }

    /**
     * Two windows with one name is the case that defeats looking the result up afterwards, so the
     * creating command reports which one it made.
     */
    @Test
    void creatingAWindowIsExactEvenWhenTheNameIsAmbiguous(Server server) {
        Window first = session(server).newWindow("same");
        Window second = session(server).newWindow("same");

        assertNotEquals(first.id(), second.id(), "two windows were made, and we know which is which");
        assertNotEquals(first.index(), second.index());
    }

    @Test
    void splittingAWindowReturnsTheNewPane(Server server) {
        Window window = session(server).windows().get(0);
        List<Pane> before = window.panes();

        Pane created = window.split();

        assertEquals(1, before.size());
        assertEquals(2, window.refresh().panes().size());
        assertTrue(
                window.refresh().panes().stream().anyMatch(pane -> pane.id().equals(created.id())),
                "the pane we were handed is one of the window's panes");
    }

    @Test
    void aPaneRunsWhatItIsSent(Server server) {
        Pane pane = session(server).windows().get(0).panes().get(0);

        pane.sendLine("echo libtmux-was-here");

        assertTrue(Await.output(pane, "libtmux-was-here"), "the pane never showed the command's output");
    }

    @Test
    void aLineThatIsAKeyNameIsTypedLiterally(Server server) {
        Pane pane = session(server).windows().get(0).panes().get(0);
        pane.sendLine("Enter() { printf 'literal-%s-command\\n' enter; }");
        // The shell has to have read the definition before the name is used. Without this the same
        // failure reports that Enter was pressed when the line was typed before anything was reading.
        pane.sendLine("echo defined-the-function");
        assertTrue(Await.output(pane, "defined-the-function"), "the shell never read the definition");
        // Clearing is what makes the marker below unambiguous, so it too has to have happened.
        pane.sendLine("clear");
        pane.sendLine("echo cleared-the-screen");
        assertTrue(Await.output(pane, "cleared-the-screen"), "the shell never reached the clear");

        pane.sendLine("Enter");
        assertDoesNotThrow(() -> pane.sendLine("-R"), "a line is not a send-keys option");

        assertTrue(Await.output(pane, "literal-enter-command"), "Enter was pressed instead of typed");
    }

    @Test
    void renamingChangesTheNameAndNotTheIdentity(Server server) {
        Session original = session(server);

        Session renamed = original.rename("renamed");

        assertEquals("renamed", renamed.name());
        assertEquals(original, renamed, "a rename does not make a different session");
        assertEquals("renamed", original.refresh().name());
    }

    @Test
    void killingRemovesItFromTheNextCapture(Server server) {
        Window extra = session(server).newWindow("doomed");

        extra.kill();

        List<String> names =
                session(server).windows().stream().map(Window::name).toList();
        assertEquals(1, names.size(), "only the fixture window is left");
        assertTrue(!names.contains("doomed"), "the killed window is gone from the next capture");
    }

    /** The title rides the capture row, so it has to survive being framed and parsed with the rest. */
    @Test
    void aPaneReportsTheTitleItWasGiven(Server server) {
        Pane pane = session(server).windows().get(0).panes().get(0);
        String before = pane.title();

        Pane retitled = pane.retitle("probe-title");

        assertEquals("probe-title", retitled.title());
        assertNotEquals(before, retitled.title(), "the title never actually changed");
        assertEquals("probe-title", pane.refresh().title(), "and the handle it was called on agrees");
        assertEquals(
                "probe-title",
                server.panes().get(0).title(),
                "a fresh capture of the whole server carries it too, so it is not a per-pane lookup");
    }

    @Test
    void refreshingSomethingThatIsGoneSaysSo(Server server) {
        Window extra = session(server).newWindow("doomed");
        extra.kill();

        assertThrows(ObjectDoesNotExist.class, extra::refresh);
    }

    @Test
    void anOperationOnSomethingAlreadyGoneRaises(Server server) {
        Window extra = session(server).newWindow("doomed");
        extra.kill();

        assertThrows(
                LibTmuxException.class,
                () -> extra.rename("later"),
                "tmux reports the missing target, and a silent no-op would hide it");
    }

    @Test
    void aHandleCannotMutateAReplacementServerThatReusedItsId(Server server) {
        Session stale = session(server);
        server.killServer();
        awaitSocketReleased(server);

        try (Server replacement = Server.open(server.config())) {
            try {
                Session current = replacement.newSession("replacement");
                assertEquals(stale.id(), current.id(), "the replacement did not reuse the id this test exercises");
                assertNotEquals(
                        stale, current, "equal numeric ids from different server processes are not one session");

                assertThrows(ObjectDoesNotExist.class, () -> stale.rename("corrupted"));

                assertEquals("replacement", replacement.sessions().get(0).name());
            } finally {
                replacement.killServer();
            }
        }
    }

    /**
     * Waits for the killed server to let go of its socket.
     *
     * <p>tmux unlinks the socket as it exits, and a client reaching one whose server is still
     * exiting is answered {@code server exited unexpectedly} rather than {@code no server running}
     * — from 3.3a onwards. A replacement on the same path has to be started after that, or the
     * test measures the teardown rather than the thing it is about.
     */
    private static void awaitSocketReleased(Server server) {
        if (!(server.config().endpoint() instanceof ServerEndpoint.SocketPath socket)) {
            return;
        }
        for (int attempt = 0; attempt < 200 && Files.exists(socket.path()); attempt++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
