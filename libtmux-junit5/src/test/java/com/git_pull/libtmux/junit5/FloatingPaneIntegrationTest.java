package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.TmuxVersion;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A field only some supported tmuxes have.
 *
 * <p>tmux gained {@code pane_floating_flag} in 3.7. Before that the format expands to nothing at
 * all — not to zero — so a client that read it ungated would report every pane as not floating on
 * five of the eight releases this library supports, which is indistinguishable from a tmux that
 * looked and found the pane was not floating.
 *
 * <p>This runs on every lane of the compatibility matrix, so both sides of the gate are executed.
 */
@ExtendWith(TmuxExtension.class)
final class FloatingPaneIntegrationTest {

    private static final TmuxVersion FLOATING_SINCE = new TmuxVersion(3, 7, "");

    @Test
    void aTmuxThatCannotSaySaysNothingRatherThanNo(Server server) {
        Pane pane = server.panes().get(0);

        if (server.version().atLeast(FLOATING_SINCE)) {
            assertEquals(
                    Optional.of(false), pane.floating(), "this tmux can answer, and an ordinary pane does not float");
        } else {
            assertEquals(
                    Optional.empty(), pane.floating(), "this tmux has no such format, and empty is the honest answer");
        }
    }

    @Test
    void theRestOfTheCaptureIsUnaffectedByTheGate(Server server) {
        Pane pane = server.panes().get(0);

        assertTrue(pane.active(), "the fixture's only pane is its window's active one");
        assertEquals(0, pane.index());
        assertTrue(pane.id().value().startsWith("%"));
    }
}
