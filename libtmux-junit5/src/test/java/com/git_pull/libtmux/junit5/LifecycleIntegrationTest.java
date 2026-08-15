package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.LibTmuxException;
import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.Window;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Making and ending sessions, and telling an empty server from an absent one. */
@ExtendWith(TmuxExtension.class)
final class LifecycleIntegrationTest {

    @Test
    void aCreatedSessionIsTheOneHandedBack(Server server) {
        Session created = server.newSession("made");

        assertEquals("made", created.name());
        assertTrue(server.hasSession("made"));
        assertEquals(2, server.sessions().size(), "the fixture session plus the one just made");
    }

    /** tmux keeps session names unique, so a duplicate is refused rather than silently made. */
    @Test
    void aDuplicateSessionNameIsRefusedByTmux(Server server) {
        server.newSession("twin");

        assertThrows(LibTmuxException.class, () -> server.newSession("twin"));
        assertEquals(
                1,
                server.sessions().stream().filter(s -> s.name().equals("twin")).count());
    }

    @Test
    void anAbsentSessionIsReportedAbsent(Server server) {
        assertFalse(server.hasSession("never-made"));
    }

    /**
     * The distinction the lenient list accessors deliberately do not make: they return an empty list
     * for both "no sessions" and "no server", and these are how a caller tells them apart.
     */
    @Test
    void anEmptyServerAndAnAbsentOneAreDistinguishable(Server server) {
        assertTrue(server.isAlive());
        server.raiseIfDead();

        server.killServer();

        assertFalse(server.isAlive(), "the server is gone");
        assertThrows(LibTmuxException.class, server::raiseIfDead);
        assertEquals(List.of(), server.sessions(), "and the lenient accessor still answers with nothing");
    }

    @Test
    void killingAServerThatIsAlreadyGoneIsNotAFailure(Server server) {
        server.killServer();

        server.killServer();
    }

    // ------------------------------------------------------------------------------- captured state

    @Test
    void aPaneReportsWhatTmuxKnowsAboutIt(Server server) {
        Pane pane = server.panes().get(0);

        assertTrue(pane.size().width() > 0, "a pane has a width: " + pane.size());
        assertTrue(pane.size().height() > 0, "a pane has a height: " + pane.size());
        assertTrue(pane.pid() > 0, "a pane runs a process");
        assertTrue(pane.currentPath().isAbsolute(), "the working directory is a real path: " + pane.currentPath());
    }

    @Test
    void aWindowCarriesItsOwnLayoutBackToTmux(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        window.split();

        String layout = window.refresh().layout();

        assertTrue(
                server.cmd("select-layout", "-t", window.id().value(), layout).succeeded(),
                "tmux must accept the layout string it produced: " + layout);
    }

    // -------------------------------------------------------------------------------- navigation

    @Test
    void theActiveWindowAndPaneAreReadableFromTheCapture(Server server) {
        Session session = server.sessions().get(0);
        Window created = session.newWindow("second");

        Session now = session.refresh();
        assertEquals(created.id(), now.activeWindow().orElseThrow().id(), "creating a window selects it");
        assertTrue(now.activePane().isPresent());
        assertTrue(now.activePane().orElseThrow().active());
    }

    @Test
    void selectingChangesWhichIsActive(Server server) {
        Session session = server.sessions().get(0);
        Window first = session.windows().get(0);
        session.newWindow("second");

        first.select();

        assertEquals(first.id(), session.refresh().activeWindow().orElseThrow().id());
    }

    @Test
    void selectingAPaneChangesWhichIsActive(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        Pane created = window.split();

        window.refresh().panes().get(0).select();

        assertFalse(
                created.refresh().active(),
                "the pane the split produced was active, and selecting the first one took that away");
    }
}
