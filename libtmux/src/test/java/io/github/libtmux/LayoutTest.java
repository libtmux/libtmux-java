package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LayoutTest {
    @Test
    void byTmuxNameFindsEveryDeclaredLayoutByItsExactName() {
        for (Layout layout : Layout.values()) {
            assertEquals(layout, Layout.byTmuxName(layout.tmuxName()).orElseThrow());
        }
    }

    @Test
    void byTmuxNameRejectsAnAbbreviationOrUnknownName() {
        assertTrue(Layout.byTmuxName("main-h").isEmpty());
        assertTrue(Layout.byTmuxName("not-a-layout").isEmpty());
        assertTrue(Layout.byTmuxName("").isEmpty());
    }
}
