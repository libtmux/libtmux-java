package io.github.libtmux.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.TmuxVersion;
import io.github.libtmux.exception.UnsupportedFeatureException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The documented limit of the feature guards: they read literal commands, not nested strings. */
final class TmuxFeaturesTest {

    private static final TmuxVersion TMUX_3_7 = TmuxVersion.parse("3.7");

    @Test
    void aLiteralNamelessBreakPaneIsRefusedOnExactThreeSeven() {
        TmuxFeatures.Intent literal = TmuxFeatures.Intent.literal(List.of(List.of("break-pane", "-d")));

        assertThrows(UnsupportedFeatureException.class, () -> TmuxFeatures.require(TMUX_3_7, literal));
    }

    @Test
    void aBreakPaneNestedInAnIfShellBodyPassesTheGuardUnchanged() {
        TmuxFeatures.Intent nested =
                TmuxFeatures.Intent.literal(List.of(List.of("if-shell", "-F", "1", "break-pane -d")));

        assertDoesNotThrow(() -> TmuxFeatures.require(TMUX_3_7, nested));
    }
}
