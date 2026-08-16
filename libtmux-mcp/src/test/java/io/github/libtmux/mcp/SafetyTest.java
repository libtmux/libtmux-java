package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What a ceiling permits, and the names an operator writes. */
final class SafetyTest {

    @Test
    void aCeilingPermitsItselfAndEverythingBelowIt() {
        assertTrue(Safety.READONLY.allows(Safety.READONLY));
        assertFalse(Safety.READONLY.allows(Safety.MUTATING));
        assertFalse(Safety.READONLY.allows(Safety.DESTRUCTIVE));

        assertTrue(Safety.MUTATING.allows(Safety.READONLY));
        assertTrue(Safety.MUTATING.allows(Safety.MUTATING));
        assertFalse(Safety.MUTATING.allows(Safety.DESTRUCTIVE));

        assertTrue(Safety.DESTRUCTIVE.allows(Safety.DESTRUCTIVE));
    }

    /** The same three words every port of libtmux takes, so one configuration covers them all. */
    @Test
    void theNamesAreTheOnesEveryPortAccepts() {
        assertEquals("readonly", Safety.READONLY.wireName());
        assertEquals("mutating", Safety.MUTATING.wireName());
        assertEquals("destructive", Safety.DESTRUCTIVE.wireName());

        for (Safety safety : Safety.values()) {
            assertEquals(safety, Safety.ofWireName(safety.wireName()));
        }
    }

    @Test
    void aNameNobodyRecognisesSaysWhatWasExpected() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Safety.ofWireName("safe"));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("readonly"), message);
        assertTrue(message.contains("destructive"), message);
    }
}
