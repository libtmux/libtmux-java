package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The modes a pane can be put into, and getting back out of them.
 *
 * <p>Every one of these works on a server with no client attached, which is not obvious: a chooser
 * is something a client draws. tmux sets the mode on the pane regardless, and a client renders it
 * whenever one arrives. The two that need something to choose from are the exception, and say so by
 * doing nothing.
 */
@ExtendWith(TmuxExtension.class)
final class PaneModeIntegrationTest {

    @Test
    void aFreshPaneIsInNoModeAtAll(Server server) {
        assertEquals(Optional.empty(), onlyPane(server).mode());
    }

    @Test
    void eachModeReportsItselfByTmuxsOwnName(Server server) {
        assertEquals(Optional.of("copy-mode"), enter(server, Pane::copyMode));
        assertEquals(Optional.of("clock-mode"), enter(server, Pane::clockMode));
        assertEquals(Optional.of("tree-mode"), enter(server, Pane::chooseTree));
        assertEquals(Optional.of("options-mode"), enter(server, Pane::customizeMode));
    }

    /**
     * tmux quits any mode with {@code copy-mode -q}, not only copy mode. A clock and a chooser leave
     * the same way, which is why the method here is not named after copying.
     */
    @Test
    void leavingWorksWhicheverModeThePaneIsIn(Server server) {
        for (java.util.function.Consumer<Pane> mode : java.util.List.<java.util.function.Consumer<Pane>>of(
                Pane::copyMode, Pane::clockMode, Pane::chooseTree, Pane::customizeMode)) {
            Pane pane = onlyPane(server);
            mode.accept(pane);
            assertTrue(pane.mode().isPresent(), "the pane never entered the mode");

            pane.exitMode();

            assertEquals(Optional.empty(), pane.mode(), "the pane did not leave the mode");
        }
    }

    @Test
    void leavingAModeThePaneWasNeverInIsNotAFailure(Server server) {
        Pane pane = onlyPane(server);

        pane.exitMode();

        assertEquals(Optional.empty(), pane.mode());
    }

    /**
     * tmux declines to show a chooser with nothing in it, and reports that by succeeding and leaving
     * the pane alone. Asking the pane is the only way to tell that from having entered.
     */
    @Test
    void theBufferChooserOpensOnlyOnceThereIsABufferToChoose(Server server) {
        Pane pane = onlyPane(server);

        pane.chooseBuffer();
        assertEquals(Optional.empty(), pane.mode(), "there is nothing to choose yet");

        server.buffers().set("chooser-fodder", "something");
        pane.chooseBuffer();

        assertEquals(Optional.of("buffer-mode"), pane.mode());
    }

    @Test
    void theClientChooserLeavesADetachedPaneAlone(Server server) {
        // The chooser has nothing to offer only while nothing is attached. A control carrier
        // attaches a client to carry commands at all, which gives the chooser something to list.
        assumeTrue(server.clients().isEmpty(), "a carrier has a client attached, so there is one to choose");

        Pane pane = onlyPane(server);

        pane.chooseClient();

        assertEquals(Optional.empty(), pane.mode(), "no client is attached, so there is none to choose");
    }

    // ------------------------------------------------------------------------------ find-window

    /**
     * Despite the name it selects nothing: the active window is where it was, and the pane is in the
     * browser instead. The flags held still from 3.2a to 3.7b, so this is behaviour and not a
     * version rule.
     *
     * <p>Compared by id rather than by name. {@code automatic-rename} takes a window's name from
     * what its pane is running, so entering the browser renames the window to {@code [tmux]} — the
     * label moves even though the window does not.
     */
    @Test
    void findingAWindowOpensTheBrowserRatherThanGoingThere(Server server) {
        Session session = server.sessions().get(0);
        session.newWindow(w -> w.named("editor").detached());
        var activeBefore = session.refresh().activeWindow().orElseThrow().id();
        Pane pane = onlyPane(server);

        pane.findWindowByName("editor");

        assertEquals(Optional.of("tree-mode"), pane.mode(), "the pane is in the browser");
        assertEquals(
                activeBefore,
                session.refresh().activeWindow().orElseThrow().id(),
                "and the active window did not move");
    }

    /** A match that found nothing enters the browser too, which is why nothing here claims it did. */
    @Test
    void aMatchThatFoundNothingIsNotReported(Server server) {
        Pane pane = onlyPane(server);

        pane.findWindowByName("no-window-carries-this");

        assertEquals(Optional.of("tree-mode"), pane.mode(), "tmux opens the browser either way");
    }

    @Test
    void aWindowCanBeSoughtByNameOrByContentOrByBoth(Server server) {
        Pane pane = onlyPane(server);

        pane.findWindow("anything");
        assertEquals(Optional.of("tree-mode"), pane.mode());
        pane.exitMode();

        pane.findWindowByContent("anything");
        assertEquals(Optional.of("tree-mode"), pane.mode());
    }

    // -------------------------------------------------------------------------------- expanding

    @Test
    void aWindowAndASessionExpandFormatsInTheirOwnContext(Server server) {
        var session = server.sessions().get(0);
        var window = session.windows().get(0);

        assertEquals(session.name(), session.expand("#{session_name}"));
        assertEquals(window.name(), window.expand("#{window_name}"));
        assertEquals(
                Integer.toString(window.index().value()),
                window.expand("#{window_index}"),
                "a window resolves its own index, not the session's active one");
    }

    private static Optional<String> enter(Server server, java.util.function.Consumer<Pane> mode) {
        Pane pane = onlyPane(server);
        pane.exitMode();
        mode.accept(pane);
        Optional<String> reported = pane.mode();
        pane.exitMode();
        return reported;
    }

    private static Pane onlyPane(Server server) {
        return server.sessions().get(0).windows().get(0).panes().get(0);
    }
}
