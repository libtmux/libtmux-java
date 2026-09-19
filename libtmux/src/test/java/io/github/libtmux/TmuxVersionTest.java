package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Parsing and ordering {@code #{version}}, including the development spelling tmux reports between
 * releases.
 */
final class TmuxVersionTest {

    @Test
    void parsesAReleaseWithAndWithoutALetter() {
        assertEquals(new TmuxVersion(3, 7, ""), TmuxVersion.parse("3.7"));
        assertEquals(new TmuxVersion(3, 7, "c"), TmuxVersion.parse("3.7c"));
        assertFalse(TmuxVersion.parse("3.7c").development());
    }

    /** The built tmux master this port has no CI lane for reports exactly this. */
    @Test
    void parsesADevelopmentBuildTrackingTowardARelease() {
        TmuxVersion parsed = TmuxVersion.parse("next-3.9");

        assertEquals(new TmuxVersion(3, 9, "", true), parsed);
        assertTrue(parsed.development());
        assertEquals("next-3.9", parsed.toString(), "toString must round-trip what tmux reported");
    }

    /** "master" names no release at all, so this must keep refusing it rather than guessing one. */
    @Test
    void refusesASpellingThatNamesNoRelease() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> TmuxVersion.parse("master"));

        assertTrue(String.valueOf(refused.getMessage()).contains("master"), String.valueOf(refused.getMessage()));
    }

    /**
     * A development build has everything its predecessor shipped and nothing the release it targets
     * adds yet, so it sorts strictly between the two.
     */
    @Test
    void aDevelopmentBuildSortsAboveItsPredecessorAndBelowItsTarget() {
        TmuxVersion next39 = TmuxVersion.parse("next-3.9");

        assertTrue(next39.atLeast(new TmuxVersion(3, 8, "")));
        assertTrue(next39.atLeast(new TmuxVersion(3, 8, "a")));
        assertFalse(next39.atLeast(new TmuxVersion(3, 9, "")));
        assertFalse(next39.atLeast(new TmuxVersion(3, 9, "a")));
    }

    /** Every version literal this library names is a release, never a development build. */
    @Test
    void aVersionConstructedFromTheThreeArgFormIsNotADevelopmentBuild() {
        assertFalse(new TmuxVersion(3, 9, "").development());
    }

    /** A release candidate is written back exactly as tmux wrote it. */
    @Test
    void aReleaseCandidateRoundTrips() {
        assertEquals("3.8-rc", TmuxVersion.parse("3.8-rc").toString());
        assertEquals("3.9-rc2", TmuxVersion.parse("3.9-rc2").toString());
        assertEquals("3.7c", TmuxVersion.parse("3.7c").toString());
    }

    /**
     * A release candidate has what its release has — tmux freezes features at the candidate, and a
     * 3.8 candidate behaves as 3.8 does on every lane-tested gate — so it compares equal to it, above
     * the development build tracking toward it. It is still not the release: not {@code equals} to it.
     */
    @Test
    void aReleaseCandidateComparesAsItsRelease() {
        TmuxVersion previous = TmuxVersion.parse("3.7c");
        TmuxVersion development = TmuxVersion.parse("next-3.8");
        TmuxVersion candidate = TmuxVersion.parse("3.8-rc");
        TmuxVersion release = TmuxVersion.parse("3.8");
        TmuxVersion patch = TmuxVersion.parse("3.8a");

        assertTrue(previous.compareTo(development) < 0);
        assertTrue(development.compareTo(candidate) < 0, "a development build precedes the candidate");
        assertEquals(0, candidate.compareTo(release), "features are frozen at the candidate");
        assertTrue(candidate.compareTo(patch) < 0);
        assertTrue(candidate.atLeast(release), "a gate on the release is met by its candidate");
        assertTrue(!candidate.equals(release), "and the candidate is still not the release");
    }
}
