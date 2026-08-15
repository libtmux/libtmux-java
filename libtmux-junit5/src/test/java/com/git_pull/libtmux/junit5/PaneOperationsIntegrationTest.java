package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.Window;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Moving panes around, including the operation that ends a tmux 3.7 server if done naively.
 *
 * <p>This runs on every lane of the matrix, which is the only reason the 3.7 case is covered at all:
 * the crash is invisible on 3.6 and on 3.7a.
 */
@ExtendWith(TmuxExtension.class)
final class PaneOperationsIntegrationTest {

    /**
     * On tmux 3.7 exactly, letting tmux name the window it breaks a pane into ends the server and
     * every session on the socket. The window still has to appear, and the server still has to be
     * there afterwards, on every supported release.
     */
    @Test
    void breakingAPaneOutLeavesTheServerStanding(Server server) {
        Session session = server.sessions().get(0);
        Window window = session.windows().get(0);
        Pane split = window.split();

        Window broken = split.breakOut();

        assertTrue(server.isAlive(), "break-pane must not take the server with it");
        assertNotEquals(window.id(), broken.id(), "the pane is in a window of its own now");
        assertEquals(1, broken.panes().size());
        assertEquals(split.id(), broken.panes().get(0).id(), "and it is the pane we broke out");
        assertEquals(2, session.refresh().windows().size());
    }

    @Test
    void breakingOutWithAChosenNameUsesIt(Server server) {
        Pane split = server.sessions().get(0).windows().get(0).split();

        Window broken = split.breakOut("chosen");

        assertEquals("chosen", broken.name());
        assertTrue(server.isAlive());
    }

    /**
     * The unnamed break must produce the name tmux itself would have chosen.
     *
     * <p>The pane's command is allowed to settle first. A freshly split pane reports whatever is
     * running while its shell starts, so comparing a value read before the break against a name
     * derived at the break is a race, not a contract.
     */
    @Test
    void theUnnamedBreakIsNamedWhatTmuxWouldHaveNamedIt(Server server) throws Exception {
        Pane split = server.sessions().get(0).windows().get(0).split();
        String command = awaitSettledCommand(split);

        Window broken = split.breakOut();

        assertEquals(command, broken.name(), "the workaround must not change what the window is called");
    }

    /** Polls until two consecutive readings agree, so the startup transient is over. */
    private static String awaitSettledCommand(Pane pane) throws InterruptedException {
        String previous = pane.refresh().currentCommand();
        for (int attempt = 0; attempt < 100; attempt++) {
            Thread.sleep(50);
            String current = pane.refresh().currentCommand();
            if (current.equals(previous)) {
                return current;
            }
            previous = current;
        }
        return previous;
    }

    @Test
    void swappingExchangesTwoPanesPositions(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        window.split();
        List<Pane> before = window.refresh().panes();
        Pane first = before.get(0);
        Pane second = before.get(1);

        first.swapWith(second);

        List<Pane> after = window.refresh().panes();
        assertEquals(second.id(), after.get(0).id(), "the second pane now sits where the first did");
        assertEquals(first.id(), after.get(1).id());
    }

    @Test
    void joiningMovesAPaneIntoAnotherWindow(Server server) {
        Session session = server.sessions().get(0);
        Window origin = session.windows().get(0);
        Window destination = session.newWindow("destination");
        Pane travelling = origin.split();

        travelling.joinTo(destination);

        assertEquals(1, origin.refresh().panes().size(), "it left");
        assertEquals(2, destination.refresh().panes().size(), "and arrived");
        assertTrue(destination.refresh().panes().stream()
                .anyMatch(pane -> pane.id().equals(travelling.id())));
    }

    @Test
    void clearingHistoryLeavesThePaneItself(Server server) {
        Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

        pane.clearHistory();

        assertEquals(pane.id(), pane.refresh().id(), "the pane survives having its scrollback dropped");
    }
}
