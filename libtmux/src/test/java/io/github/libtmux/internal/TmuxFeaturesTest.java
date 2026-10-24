package io.github.libtmux.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.libtmux.TmuxVersion;
import io.github.libtmux.exception.UnsupportedFeatureException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    @Test
    void aLiteralBreakPaneIsRefusedOnExactThreeSevenWithOrWithoutAName() {
        TmuxFeatures.Intent named = TmuxFeatures.Intent.literal(List.of(List.of("break-pane", "-d", "-n", "x")));

        assertThrows(UnsupportedFeatureException.class, () -> TmuxFeatures.require(TMUX_3_7, named));
        assertDoesNotThrow(() -> TmuxFeatures.require(TmuxVersion.parse("3.7a"), named));
    }

    /** A literal command, a release that refuses it, and a release that accepts it. */
    static Stream<Arguments> refusedShapes() {
        return Stream.of(
                arguments(List.of("select-layout", "not-a-layout"), "3.3a", "3.4"),
                arguments(List.of("refresh-client", "-l"), "3.6", "3.6a"),
                arguments(List.of("delete-buffer", "-b", "named"), "3.3a", "3.4"),
                arguments(List.of("set-buffer", "-b", "named", "-n", "renamed"), "3.3a", "3.4"),
                arguments(List.of("split-window", "-p", "30"), "3.4", "3.5"));
    }

    @ParameterizedTest
    @MethodSource("refusedShapes")
    void aLiteralCommandIsRefusedWhereItIsUnsafeAndNestedItPassesTheGuard(
            List<String> command, String affected, String unaffected) {
        TmuxVersion affectedVersion = TmuxVersion.parse(affected);
        TmuxFeatures.Intent literal = TmuxFeatures.Intent.literal(List.of(command));
        TmuxFeatures.Intent nested =
                TmuxFeatures.Intent.literal(List.of(List.of("if-shell", "-F", "1", String.join(" ", command))));

        assertThrows(UnsupportedFeatureException.class, () -> TmuxFeatures.require(affectedVersion, literal));
        assertDoesNotThrow(() -> TmuxFeatures.require(TmuxVersion.parse(unaffected), literal));
        assertDoesNotThrow(() -> TmuxFeatures.require(affectedVersion, nested));
    }

    @Test
    void aNameWithAColonIsRefusedOnExactThreeSevenAndNestedItPassesTheGuard() {
        TmuxFeatures.Intent literal = TmuxFeatures.Intent.literal(List.of(List.of("new-window", "-n", "a:b")));
        TmuxFeatures.Intent nested =
                TmuxFeatures.Intent.literal(List.of(List.of("if-shell", "-F", "1", "new-window -n a:b")));

        assertThrows(IllegalArgumentException.class, () -> TmuxFeatures.require(TMUX_3_7, literal));
        assertDoesNotThrow(() -> TmuxFeatures.require(TmuxVersion.parse("3.7a"), literal));
        assertDoesNotThrow(() -> TmuxFeatures.require(TMUX_3_7, nested));
    }
}
