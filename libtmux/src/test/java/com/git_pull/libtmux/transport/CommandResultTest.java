package com.git_pull.libtmux.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A nonzero exit is data. Only a higher-level method decides whether it should raise. */
final class CommandResultTest {

    @Test
    void aNonzeroExitIsReportedRatherThanThrown() {
        CommandResult failed = new CommandResult(1, List.of(), List.of("no such pane"));

        assertFalse(failed.succeeded());
        assertEquals(List.of("no such pane"), failed.stderr());
        assertTrue(new CommandResult(0, List.of(), List.of()).succeeded());
    }

    @Test
    void mutatingTheListsAfterConstructionCannotChangeTheResult() {
        List<String> stdout = new ArrayList<>(List.of("%1"));
        CommandResult result = new CommandResult(0, stdout, List.of());

        stdout.add("%2");

        assertEquals(List.of("%1"), result.stdout());
    }

    @Test
    void capturedOutputIsUnmodifiable() {
        CommandResult result = new CommandResult(0, List.of("%1"), List.of());

        assertThrows(UnsupportedOperationException.class, () -> result.stdout().add("%2"));
    }

    @Test
    void toStringExposesNeitherCapturedContentNorErrorText() {
        CommandResult result = new CommandResult(1, List.of("export TOKEN=hunter2"), List.of("no such pane"));

        String rendered = result.toString();

        assertFalse(rendered.contains("hunter2"), "captured pane content must not reach a log line: " + rendered);
        assertEquals("CommandResult[exitCode=1, stdoutLines=1, stderrLines=1]", rendered);
    }
}
