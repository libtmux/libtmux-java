package io.github.libtmux.workspace.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class LoadProgressTest {

    /** Before any window is known, the pane count must read 0, not an empty, double-spaced gap. */
    @Test
    void progressTokenNamesAZeroPaneCountBeforeTheFirstWindow() {
        assertEquals("0/1 win, 0 pane", LoadProgress.progressToken(0, 1, 0, 0));
    }

    @Test
    void progressTokenReportsBothRatiosOnceKnown() {
        assertEquals("1/2 win, 3/5 pane", LoadProgress.progressToken(1, 2, 3, 5));
    }
}
