package io.github.libtmux.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.transport.OperationReport;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ErrorTextTest {

    @Test
    void quotesWhatTmuxPrintedAfterAColon() {
        assertEquals(
                ": can't find pane: %9; no such file",
                ErrorText.suffix(List.of("can't find pane: %9", " ", "no such file")));
    }

    @Test
    void addsNothingWhenTmuxPrintedNothing() {
        assertEquals("", ErrorText.suffix(List.of("", "  ")));
    }

    @Test
    void capsTheQuoteSoAMessageStaysALogLine() {
        String suffix = ErrorText.suffix(List.of("y".repeat(OperationReport.ERROR_LIMIT * 3)));

        assertTrue(suffix.endsWith("…"), suffix);
        assertEquals(": ".length() + OperationReport.ERROR_LIMIT + 1, suffix.length());
    }
}
