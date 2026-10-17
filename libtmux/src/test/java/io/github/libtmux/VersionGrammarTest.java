package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.exception.MalformedResponseException;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.internal.support.Versions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The version strings tmux has ever reported, and what a support policy does with each: the
 * canonical vectors every port is held to, with floor 3.3a.
 */
final class VersionGrammarTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "3.3a",
                "3.4",
                "3.7d",
                "3.8",
                "3.10",
                "4.0",
                "3.4-rc",
                "3.8-rc",
                "3.8-rc2",
                "next-3.4",
                "next-3.9"
            })
    void supportedAtTheFloor(String reported) {
        assertEquals(reported, Versions.require(reported).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.3", "3.2a", "3.3-rc", "3.2-rc5", "next-3.3"})
    void belowTheFloorIsRefusedAsUnsupported(String reported) {
        assertThrows(UnsupportedFeatureException.class, () -> Versions.require(reported));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "master",
                "openbsd-7.6",
                "",
                "3",
                "garbage",
                "3.3a garbage",
                "3.3a-master",
                "3.3a-dev",
                "next-3.3a",
                "next-3.9-master"
            })
    void unparseableReportsAreRefusedAsMalformed(String reported) {
        assertThrows(MalformedResponseException.class, () -> Versions.require(reported));
    }

    @Test
    void orderingFollowsTheReleaseTrain() {
        assertAscending("next-3.7", "3.7-rc", "3.7-rc3", "3.7", "3.7a");
        assertAscending("next-3.4", "3.4-rc", "3.4");
        assertAscending("3.8-rc2", "3.8-rc10", "3.8");
        assertAscending("3.9", "3.10", "4.0");
    }

    @Test
    void aCandidateOrDevelopmentBuildOfAReleaseIsNotThatRelease() {
        TmuxVersion control = new TmuxVersion(3, 7, "");
        for (String below : List.of("next-3.7", "3.7-rc", "3.7-rc2")) {
            assertTrue(TmuxVersion.parse(below).compareTo(control) < 0, below);
        }
        for (String atOrAbove : List.of("3.7", "3.7a", "3.8-rc2", "next-3.8")) {
            assertTrue(TmuxVersion.parse(atOrAbove).compareTo(control) >= 0, atOrAbove);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.3a garbage", "3.3a-master", "3.3a-dev", "3.3abc", "next-3.3a-x", "", "3", "master"})
    void parseIsAnchoredToTheCanonicalGrammar(String text) {
        assertThrows(IllegalArgumentException.class, () -> TmuxVersion.parse(text));
    }

    @Test
    void theNaturalOrderAgreesWithEqualsButAFeatureGateCountsACandidateAsItsRelease() {
        TmuxVersion release = TmuxVersion.parse("3.8");
        TmuxVersion candidate = TmuxVersion.parse("3.8-rc2");
        assertTrue(candidate.compareTo(release) < 0);
        assertTrue(!candidate.equals(release));
        assertTrue(candidate.atLeast(release), "a candidate ships what its release ships");
        assertTrue(candidate.sameRelease(release));
        assertTrue(!TmuxVersion.parse("next-3.8").sameRelease(release));
        assertTrue(!TmuxVersion.parse("3.8a").sameRelease(release));
        assertEquals(0, release.compareTo(TmuxVersion.parse("3.8")));
    }

    private static void assertAscending(String... versions) {
        for (int index = 1; index < versions.length; index++) {
            TmuxVersion lower = TmuxVersion.parse(versions[index - 1]);
            TmuxVersion higher = TmuxVersion.parse(versions[index]);
            assertTrue(lower.compareTo(higher) < 0, versions[index - 1] + " < " + versions[index]);
            assertTrue(higher.compareTo(lower) > 0, versions[index] + " > " + versions[index - 1]);
        }
    }
}
