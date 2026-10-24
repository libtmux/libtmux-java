package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class NamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "a:b", "x.", ":y"})
    void exactly37RefusesADelimiterButEveryOtherReleaseKeepsIt(String name) {
        assertThrows(IllegalArgumentException.class, () -> Names.require(name, TmuxVersion.parse("3.7")));
        assertThrows(IllegalArgumentException.class, () -> Names.require(name, TmuxVersion.parse("3.7-rc2")));
        for (String other : new String[] {"3.3a", "3.6b", "3.7a", "3.7d", "3.8", "next-3.7", "next-3.9"}) {
            assertEquals(name, Names.require(name, TmuxVersion.parse(other)), other);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "with space", "under_score"})
    void aPlainNameIsAlwaysAccepted(String name) {
        assertEquals(name, Names.require(name, TmuxVersion.parse("3.7")));
    }
}
