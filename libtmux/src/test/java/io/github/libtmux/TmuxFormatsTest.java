package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The per-release text rules, one release at a time.
 *
 * <p>Each rule was measured against the release it names; {@code QuotingPropertyIntegrationTest}
 * checks the same text against every release in the matrix. This pins the boundaries, so a rule that
 * starts or stops a release early fails here with the release named.
 */
final class TmuxFormatsTest {

    private static final TmuxVersion V3_3A = new TmuxVersion(3, 3, "a");
    private static final TmuxVersion V3_4 = new TmuxVersion(3, 4, "");
    private static final TmuxVersion V3_5 = new TmuxVersion(3, 5, "");
    private static final TmuxVersion V3_6 = new TmuxVersion(3, 6, "");
    private static final TmuxVersion V3_7 = new TmuxVersion(3, 7, "");

    @Test
    void aLiteralDoublesEveryHash() {
        assertEquals("##{pane_id} ##", TmuxFormats.literal("#{pane_id} #"));
    }

    /** 3.4 alone escapes a {@code $} that a letter, {@code _}, or <code>{</code> follows. */
    @Test
    void onlyThreeFoursDollarEscapeIsUndone() {
        String printed = "\\$HOME \\$_x \\${y} $1 $ \\\\$HOME";

        assertEquals("$HOME $_x ${y} $1 $ \\$HOME", TmuxFormats.printed(printed, V3_4));
        assertEquals(printed, TmuxFormats.printed(printed, V3_3A));
        assertEquals(printed, TmuxFormats.printed(printed, V3_5));
    }

    /** 3.4 through 3.5a print a control character as an escape a typed one cannot be told from. */
    @Test
    void aPrintedControlEscapeIsLeftAsPrinted() {
        assertEquals("a\\033b", TmuxFormats.printed("a\\033b", V3_4));
        assertEquals("a\\033b", TmuxFormats.printed("a\\033b", V3_5));
        assertEquals(List.of("x", "\\001"), TmuxFormats.printed(List.of("x", "\\001"), V3_4));
    }

    @Test
    void aNameIsLookedUpAsEachReleaseStoresIt() {
        assertEquals(List.of("a_b_c", "a.b:c"), TmuxFormats.storedNames("a.b:c", V3_6));
        assertEquals(List.of("a.b:c"), TmuxFormats.storedNames("a.b:c", V3_7));
        assertEquals(List.of("tab\\there", "tab\there"), TmuxFormats.storedNames("tab\there", V3_6));
        assertEquals(List.of("\\\\x", "\\x"), TmuxFormats.storedNames("\\x", V3_7));
        assertEquals(List.of("\\033", "\033"), TmuxFormats.storedNames("\033", V3_5));
    }

    /** Before 3.5 the stored form also escapes the {@code $}; 3.5 stopped. */
    @Test
    void aStoredNameEscapesItsDollarBeforeThreeFive() {
        assertEquals(List.of("\\$HOME", "$HOME"), TmuxFormats.storedNames("$HOME", V3_4));
        assertEquals(List.of("$HOME"), TmuxFormats.storedNames("$HOME", V3_5));
        assertEquals(List.of("$1"), TmuxFormats.storedNames("$1", V3_3A));
    }
}
