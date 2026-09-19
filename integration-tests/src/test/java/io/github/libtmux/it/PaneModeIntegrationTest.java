package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.PaneMode;
import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The modes a pane can be put into, and getting back out of them.
 *
 * <p>The library enters only copy mode itself. The others — a clock, the browsers — are for a person
 * at an attached client, and are entered here through {@code cmd} to show that a pane reports them and
 * leaves them all the same way. Every one works on a server with no client attached, which is not
 * obvious: a chooser is something a client draws, and tmux sets the mode on the pane regardless.
 */
@ExtendWith(TmuxExtension.class)
final class PaneModeIntegrationTest {

    @Test
    void aFreshPaneIsInNoModeAtAll(Server server) {
        assertEquals(Optional.empty(), onlyPane(server).mode());
    }

    @Test
    void eachModeReportsItselfByTmuxsOwnName(Server server) {
        assertEquals(Optional.of(PaneMode.COPY), enter(server, Pane::copyMode));
        assertEquals(Optional.of(PaneMode.CLOCK), enter(server, by("clock-mode")));
        assertEquals(Optional.of(PaneMode.TREE), enter(server, by("choose-tree")));
        assertEquals(Optional.of(PaneMode.OPTIONS), enter(server, by("customize-mode")));
    }

    /**
     * tmux quits any mode with {@code copy-mode -q}, not only copy mode. A clock and a chooser leave
     * the same way, which is why the method here is not named after copying.
     */
    @Test
    void leavingWorksWhicheverModeThePaneIsIn(Server server) {
        for (Consumer<Pane> mode :
                List.<Consumer<Pane>>of(Pane::copyMode, by("clock-mode"), by("choose-tree"), by("customize-mode"))) {
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

    /** Enters a mode the way a person would, through tmux's own command for it. */
    private static Consumer<Pane> by(String command) {
        return pane -> pane.server().cmd(command, "-t", pane.id().value());
    }

    private static Optional<PaneMode> enter(Server server, Consumer<Pane> mode) {
        Pane pane = onlyPane(server);
        pane.exitMode();
        mode.accept(pane);
        Optional<PaneMode> reported = pane.mode();
        pane.exitMode();
        return reported;
    }

    private static Pane onlyPane(Server server) {
        return server.sessions().get(0).windows().get(0).panes().get(0);
    }
}
