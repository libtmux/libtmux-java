package com.git_pull.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.LibTmuxException;
import com.git_pull.libtmux.ObjectDoesNotExist;
import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.Window;
import com.git_pull.libtmux.junit5.TmuxExtension;
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

        assertTrue(awaitOutput(pane, "libtmux-was-here"), "the pane never showed the command's output");
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

    private static boolean awaitOutput(Pane pane, String expected) {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (pane.capture().stream().anyMatch(line -> line.contains(expected))) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
