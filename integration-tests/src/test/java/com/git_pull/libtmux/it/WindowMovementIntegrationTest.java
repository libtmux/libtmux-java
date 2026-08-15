package com.git_pull.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.git_pull.libtmux.LibTmuxException;
import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.PaneEdges;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.Window;
import com.git_pull.libtmux.junit5.TmuxExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Moving windows between sessions, and where a pane sits inside its window. */
@ExtendWith(TmuxExtension.class)
final class WindowMovementIntegrationTest {

    @Test
    void linkingPutsOneWindowInTwoSessions(Server server) {
        Session origin = server.sessions().get(0);
        Session other = server.newSession("other");
        Window shared = origin.windows().get(0);

        shared.linkTo(other);

        List<Window> links = server.windows().stream()
                .filter(window -> window.id().equals(shared.id()))
                .toList();
        assertEquals(2, links.size(), "one window, two winlinks");
        assertTrue(links.stream().allMatch(Window::linked), "and tmux says so");
    }

    @Test
    void unlinkingLeavesTheOtherLink(Server server) {
        Session origin = server.sessions().get(0);
        Session other = server.newSession("other");
        Window shared = origin.windows().get(0);
        shared.linkTo(other);

        List<Window> links = server.windows().stream()
                .filter(window -> window.id().equals(shared.id()))
                .toList();
        links.get(1).unlink();

        assertEquals(
                1,
                server.windows().stream()
                        .filter(window -> window.id().equals(shared.id()))
                        .count(),
                "the window is still linked once");
    }

    @Test
    void unlinkingTheOnlyLinkIsRefused(Server server) {
        Window only = server.sessions().get(0).windows().get(0);

        assertThrows(
                LibTmuxException.class,
                only::unlink,
                "tmux refuses to leave a window linked nowhere, and that refusal must reach the caller");
    }

    @Test
    void movingTakesTheWindowWithIt(Server server) {
        Session origin = server.sessions().get(0);
        Session other = server.newSession("other");
        Window travelling = origin.newWindow("travelling");

        travelling.moveTo(other);

        assertFalse(
                origin.refresh().windows().stream()
                        .anyMatch(window -> window.id().equals(travelling.id())),
                "it left");
        assertTrue(
                other.refresh().windows().stream()
                        .anyMatch(window -> window.id().equals(travelling.id())),
                "and arrived");
    }

    @Test
    void rotatingMovesThePanesAround(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        window.split();
        List<Pane> before = window.refresh().panes();

        window.rotate();

        List<Pane> after = window.refresh().panes();
        assertEquals(before.size(), after.size(), "rotating moves panes, it does not add or remove them");
        assertEquals(
                before.get(1).id(), after.get(0).id(), "the pane that was second now sits where the first one was");
    }

    @Test
    void respawningKeepsTheWindowAndReplacesWhatRuns(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        long before = window.panes().get(0).pid();

        window.respawn();

        Window now = window.refresh();
        assertEquals(window.id(), now.id(), "the window survives");
        assertEquals(1, now.panes().size());
        assertTrue(now.panes().get(0).pid() != before, "but what runs in it was started again");
    }

    /** tmux draws a popup for a client, and a detached fixture session has none. */
    @Test
    void aPopupWithoutAClientIsReportedRatherThanIgnored(Server server) {
        // A popup needs a client to draw on, and this is about what happens when there is none.
        // A control carrier attaches one to carry commands at all, so under that carrier the
        // premise cannot hold and there is nothing here to test rather than something failing.
        assumeTrue(server.clients().isEmpty(), "a carrier has a client attached, so a popup has one");

        Window window = server.sessions().get(0).windows().get(0);

        assertThrows(LibTmuxException.class, () -> window.displayPopup("true"));
    }

    // ------------------------------------------------------------------------------ pane edges

    @Test
    void aLonePaneTouchesEverySide(Server server) {
        PaneEdges edges = server.panes().get(0).edges();

        assertTrue(edges.fillsWindow(), "the only pane in a window fills it: " + edges);
    }

    @Test
    void aSplitMeansNeitherPaneFillsTheWindow(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        window.split();

        List<PaneEdges> edges =
                window.refresh().panes().stream().map(Pane::edges).toList();

        assertEquals(2, edges.size());
        assertFalse(edges.get(0).fillsWindow(), "a split pane cannot touch all four sides");
        assertFalse(edges.get(1).fillsWindow());
        assertTrue(
                edges.get(0).top() != edges.get(1).top()
                        || edges.get(0).left() != edges.get(1).left(),
                "the two panes sit on different sides of the divider");
    }

    @Test
    void copyModeIsEnteredWithoutLosingThePane(Server server) {
        Pane pane = server.panes().get(0);

        pane.copyMode();

        assertEquals(pane.id(), pane.refresh().id(), "entering copy mode does not replace the pane");
    }

    // ---------------------------------------------------------------------------- key bindings

    @Test
    void aBoundKeyAppearsInTheListingAndCanBeRemoved(Server server) {
        server.bindKey("F12", List.of("new-window", "-d", "-n", "bound"));

        assertTrue(server.listKeys().stream().anyMatch(line -> line.contains("F12")), "the binding is listed");

        server.unbindKey("F12");
        assertFalse(server.listKeys().stream().anyMatch(line -> line.contains("F12")));
    }
}
