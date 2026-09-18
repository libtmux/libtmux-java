package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Dimensions;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.WakeReason;
import io.github.libtmux.junit5.TmuxExtension;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What {@code awaitText} must not mistake for the pane's answer.
 *
 * <p>Reading a screen is a heuristic, and two things make it the wrong answer rather than a late
 * one. A terminal echoes what was typed at it, so a wait for text the command's own line contains
 * is satisfied before the command has done anything. And a terminal breaks a long line at the
 * pane's width, so text wider than the pane is in no single row and a wait watching rows never sees
 * what is in plain view.
 *
 * <p>Each pane here runs a plain shell with a two-character prompt at a stated width. A login
 * shell's own prompt is long enough to wrap the command being typed, which hides the first failure
 * behind the second.
 */
@ExtendWith(TmuxExtension.class)
final class PaneWaitFidelityIntegrationTest {

    private static Pane shell(Server server, String name, int columns) throws InterruptedException {
        Pane pane = server.newSession(session -> session.named(name)
                        .running("env", "PS1=$ ", "ENV=", "/bin/sh")
                        .sized(new Dimensions(columns, 24)))
                .windows()
                .get(0)
                .panes()
                .get(0);
        assertEquals(WakeReason.SIGNALLED, pane.awaitText("$", Duration.ofSeconds(10)), "the shell drew a prompt");
        return pane;
    }

    @Test
    void aCommandsOwnEchoIsNotItsOutput(Server server) throws InterruptedException {
        Pane pane = shell(server, "echo", 120);

        pane.sendLine("sleep 5; echo build-finished");
        long started = System.nanoTime();
        WakeReason why = pane.awaitText("build-finished", Duration.ofSeconds(3));
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(
                WakeReason.TIMED_OUT,
                why,
                "the only thing on screen is the echo of the command, which cannot print for five seconds");
        assertTrue(millis >= 2_500, "and the wait really waited rather than matching at once: " + millis + "ms");
    }

    @Test
    void whatTheCommandActuallyPrintsStillMatches(Server server) throws InterruptedException {
        Pane pane = shell(server, "output", 120);

        pane.sendLine("printf 'build-finished\\n'");

        assertEquals(
                WakeReason.SIGNALLED,
                pane.awaitText("build-finished", Duration.ofSeconds(10)),
                "suppressing the echo must not suppress the output, which here is the same text");
    }

    @Test
    void textTheTerminalBrokeAcrossRowsIsFound(Server server) throws InterruptedException {
        Pane pane = shell(server, "wrap", 30);
        String marker = "MARK-0123456789-ABCDEFGHIJ-0123456789-END";

        // Assembled from two halves, so the command's own text never contains the marker whole and
        // this cannot pass on the echo.
        pane.sendLine("printf '%s%s\\n' MARK-0123456789-ABCDE FGHIJ-0123456789-END");

        assertEquals(
                WakeReason.SIGNALLED,
                pane.awaitText(marker, Duration.ofSeconds(10)),
                "the marker is 41 characters in a 30-column pane, so no row holds all of it");
    }

    @Test
    void captureItselfStillReportsRowsAsTheyAreDisplayed(Server server) throws InterruptedException {
        Pane pane = shell(server, "rows", 30);

        pane.sendLine("printf '%s%s\\n' MARK-0123456789-ABCDE FGHIJ-0123456789-END");
        assertEquals(
                WakeReason.SIGNALLED,
                pane.awaitText("MARK-0123456789-ABCDEFGHIJ-0123456789-END", Duration.ofSeconds(10)));

        List<String> rows = pane.capture();

        assertTrue(
                rows.stream().anyMatch(row -> row.contains("MARK-0123456789-ABCDEFGHIJ-012")),
                "capture answers with what the pane displays; only the wait rejoins rows: " + rows);
        assertTrue(
                rows.stream().noneMatch(row -> row.contains("MARK-0123456789-ABCDEFGHIJ-0123456789-END")),
                "so no single row holds the whole marker: " + rows);
    }
}
