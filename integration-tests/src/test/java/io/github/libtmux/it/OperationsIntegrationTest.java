package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.ObjectDoesNotExist;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.junit5.TmuxExtension;
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

    /**
     * The title rides the capture row rather than a lookup of its own, so a stale handle keeps
     * reporting the old one until it is refreshed. That is the whole reason to check it here rather
     * than trust {@code #{pane_title}}: the value has to survive being framed and parsed with
     * everything else on the row.
     *
     * <p>Set through a raw command because the API has a getter and no setter. If that asymmetry is
     * wrong it is a library gap, not a gap in this test.
     */
    @Test
    void aPaneReportsTheTitleItWasGiven(Server server) {
        Pane pane = session(server).windows().get(0).panes().get(0);
        String before = pane.title();

        assertTrue(
                server.cmd("select-pane", "-t", pane.id().value(), "-T", "probe-title")
                        .succeeded(),
                "tmux refused to set the title");

        assertEquals("probe-title", pane.refresh().title());
        assertNotEquals(before, pane.refresh().title(), "the title never actually changed");
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
