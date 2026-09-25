package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.exception.UnsupportedFeatureException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Which strings {@link Layouts} lets through, decided without a tmux process: every case here is a
 * pure function of a layout string and a version, matching what {@code layout_set_lookup} and {@code
 * layout_construct} do in tmux's own source.
 */
final class LayoutsTest {

    private static final TmuxVersion V3_7C = new TmuxVersion(3, 7, "c");
    private static final TmuxVersion V3_3A = new TmuxVersion(3, 3, "a");
    private static final TmuxVersion V3_8 = new TmuxVersion(3, 8, "");
    private static final TmuxVersion NEXT_3_9 = TmuxVersion.parse("next-3.9");

    private static final String JSON_LAYOUT = "{\"V\":2,\"L\":{\"t\":\"v\",\"w\":80,\"h\":24,\"i\":\"0\"}}";

    // ------------------------------------------------------------------------------ JSON

    @Test
    void aJsonLayoutIsAcceptedFromTheVersionThatWritesIt() {
        assertEquals(JSON_LAYOUT, Layouts.require(JSON_LAYOUT, V3_8));
        assertEquals(JSON_LAYOUT, Layouts.require(JSON_LAYOUT, NEXT_3_9));
    }

    /**
     * Before this round, {@code require(String, TmuxVersion)} had no JSON branch at all, so a JSON
     * layout was refused as an unrecognised name on every version, including ones that write it.
     */
    @Test
    void aJsonLayoutIsRefusedBelowTheVersionThatWritesIt() {
        UnsupportedFeatureException refused =
                assertThrows(UnsupportedFeatureException.class, () -> Layouts.require(JSON_LAYOUT, V3_7C));

        assertTrue(String.valueOf(refused.getMessage()).contains("3.8"), refused.getMessage());
    }

    @Test
    void malformedJsonShapeIsStillARefusalNotACrash() {
        assertThrows(IllegalArgumentException.class, () -> Layouts.require("{not json", V3_8));
    }

    // ------------------------------------------------------------------------ prefixes

    @Test
    void aUniquePrefixIsAcceptedOnEveryVersion() {
        assertEquals("tile", Layouts.require("tile", V3_3A));
        assertEquals("tile", Layouts.require("tile", V3_7C));
        assertEquals("even-h", Layouts.require("even-h", V3_3A));
        assertEquals("even-h", Layouts.require("even-h", V3_7C));
    }

    /**
     * {@code layout_set_lookup} is compiled from a fixed table: {@code main-horizontal-mirrored}
     * does not exist in a build older than 3.5, so {@code main-h} has exactly one candidate there.
     * From 3.5 on, the mirrored variant exists too and the same text is ambiguous — confirmed
     * against the matrix (3.3a: rc 0; 3.7c: {@code invalid layout: main-h}).
     */
    @Test
    void aPrefixThatIsUniqueOnlyOnAnOlderReleaseIsAcceptedThereAndRefusedLater() {
        assertEquals("main-h", Layouts.require("main-h", V3_3A));

        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Layouts.require("main-h", V3_7C));
        assertTrue(String.valueOf(refused.getMessage()).contains("main-horizontal"), refused.getMessage());
        assertTrue(String.valueOf(refused.getMessage()).contains("main-horizontal-mirrored"), refused.getMessage());
    }

    @Test
    void aPrefixAmbiguousOnEveryVersionNamesTheCandidates() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Layouts.require("even-", V3_7C));

        assertTrue(String.valueOf(refused.getMessage()).contains("even-horizontal"), refused.getMessage());
        assertTrue(String.valueOf(refused.getMessage()).contains("even-vertical"), refused.getMessage());
    }

    @Test
    void anExactNameIsStillGatedByVersionEvenThoughItIsAlsoAPrefixOfAnother() {
        // main-horizontal is a prefix of main-horizontal-mirrored, but the exact name is not new.
        assertEquals("main-horizontal", Layouts.require("main-horizontal", V3_3A));

        assertThrows(UnsupportedFeatureException.class, () -> Layouts.require("main-horizontal-mirrored", V3_3A));
    }

    @Test
    void theVersionlessOverloadAcceptsAPrefixTooButCannotNarrowByVersion() {
        assertEquals("tile", Layouts.require("tile"));
        assertEquals("even-h", Layouts.require("even-h"));

        assertThrows(IllegalArgumentException.class, () -> Layouts.require("not-a-real-layout"));
    }

    /**
     * No refusal may claim tmux does not know a layout when tmux does. {@code main-h} is a name
     * tmux 3.3a resolves without ambiguity (confirmed above); the versionless overload cannot narrow
     * by version, but it must still say the prefix is ambiguous, not that tmux has never heard of it.
     */
    @Test
    void theVersionlessOverloadNamesAnAmbiguousPrefixRatherThanCallingItUnknown() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Layouts.require("main-h"));

        assertTrue(String.valueOf(refused.getMessage()).contains("main-horizontal"), refused.getMessage());
        assertTrue(String.valueOf(refused.getMessage()).contains("main-horizontal-mirrored"), refused.getMessage());
        assertFalse(
                String.valueOf(refused.getMessage()).contains("not a tmux layout"),
                "tmux does know this prefix, just not unambiguously without a version: " + refused.getMessage());
    }

    // -------------------------------------------------------------------------------- builtIn

    @Test
    void builtInResolvesAPrefixToTheLayoutItDenotesOnThatVersion() {
        assertEquals(Optional.of(Layout.MAIN_HORIZONTAL), Layouts.builtIn("main-h", V3_3A));
        assertEquals(Optional.empty(), Layouts.builtIn("main-h", V3_7C), "ambiguous from 3.5 on");
        assertEquals(Optional.of(Layout.TILED), Layouts.builtIn("tile", V3_7C));
        assertEquals(Optional.empty(), Layouts.builtIn("not-a-layout", V3_7C));
    }

    // ---------------------------------------------------------------------- unchanged behaviour

    @Test
    void aSerializedLayoutWithTheWrongChecksumIsStillRefused() {
        // Same string CommandChainIntegrationTest already pins as invalid; a regression guard that
        // the JSON and prefix changes did not loosen the classic-form check.
        assertThrows(IllegalArgumentException.class, () -> Layouts.require("0000,80x24,0,0,1", V3_7C));
    }
}
