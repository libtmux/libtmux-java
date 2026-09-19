package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Dimensions;
import io.github.libtmux.Pane;
import io.github.libtmux.PaneRun;
import io.github.libtmux.Server;
import io.github.libtmux.TextOutcome;
import io.github.libtmux.Window;
import io.github.libtmux.junit5.TmuxExtension;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Running a command in a pane to its end: the status is the shell's, and the output is only the
 * command's.
 *
 * <p>A plain {@code sh} with a two-character prompt, so the prompt cannot hide or wrap anything.
 */
@ExtendWith(TmuxExtension.class)
final class PaneRunIntegrationTest {

    private static final Duration GENEROUS = Duration.ofSeconds(20);

    private static Pane shell(Server server, String name, int columns) throws InterruptedException {
        Window window = server.newSession(session -> session.named(name).running("env", "PS1=$ ", "ENV=", "/bin/sh"))
                .windows()
                .get(0);
        window.resizeTo(new Dimensions(columns, 24));
        Pane pane = window.refresh().panes().get(0);
        assertTrue(pane.awaitText("$", Duration.ofSeconds(10)) != TextOutcome.TIMED_OUT, "the shell drew a prompt");
        return pane;
    }

    @Test
    void aCommandsOutputAndStatusComeBackWithoutThePlumbing(Server server) throws InterruptedException {
        Pane pane = shell(server, "ok", 120);

        PaneRun ran = pane.run("printf 'first\\nsecond\\n'", GENEROUS);

        assertEquals(PaneRun.Outcome.FINISHED, ran.outcome());
        assertEquals(OptionalInt.of(0), ran.exitStatus());
        assertEquals(List.of("first", "second"), ran.output(), "only what the command printed");
        assertTrue(ran.exact());
        assertTrue(ran.succeeded());
    }

    @Test
    void aFailingCommandsStatusIsItsOwn(Server server) throws InterruptedException {
        Pane pane = shell(server, "fails", 120);

        PaneRun ran = pane.run("echo about-to-fail; exit 7", GENEROUS);

        assertEquals(OptionalInt.of(7), ran.exitStatus());
        assertEquals(List.of("about-to-fail"), ran.output());
        assertFalse(ran.succeeded());
        assertEquals(
                PaneRun.Outcome.FINISHED,
                pane.run("true", GENEROUS).outcome(),
                "and exit ended the command's subshell, not the pane's shell");
    }

    @Test
    void aCommandThatPrintsNothingAnswersWithNothing(Server server) throws InterruptedException {
        PaneRun ran = shell(server, "quiet", 120).run("true", GENEROUS);

        assertEquals(List.of(), ran.output());
        assertTrue(ran.exact());
        assertTrue(ran.succeeded());
    }

    /** Quotes, dollars and a backslash reach the command as themselves. */
    @Test
    void theCommandIsRunAsWritten(Server server) throws InterruptedException {
        Pane pane = shell(server, "quoting", 120);

        PaneRun ran = pane.run("x='it'\\''s'; printf '%s %s\\n' \"$x\" 'a\\b'", GENEROUS);

        assertEquals(List.of("it's a\\b"), ran.output());
    }

    /** A line wider than the pane is the line the command printed, not the rows the terminal drew. */
    @Test
    void aLineWiderThanThePaneComesBackWhole(Server server) throws InterruptedException {
        Pane pane = shell(server, "wide", 30);
        String wide = "w".repeat(70);

        PaneRun ran = pane.run("printf '%s\\n' " + wide, GENEROUS);

        assertEquals(List.of(wide), ran.output());
        assertTrue(ran.exact(), "and the typed line, which wrapped too, was not mistaken for a marker");
    }

    @Test
    void aCommandStillRunningAtTheDeadlineIsATimeout(Server server) throws InterruptedException {
        Pane pane = shell(server, "slow", 120);

        PaneRun ran = pane.run("echo started; sleep 30", Duration.ofMillis(1500));

        assertEquals(PaneRun.Outcome.TIMED_OUT, ran.outcome());
        assertEquals(OptionalInt.empty(), ran.exitStatus());
        assertFalse(ran.exact());
        assertEquals(List.of("started"), ran.output(), "what it had printed by the deadline");
    }

    @Test
    void aPaneNotRunningAShellIsRefused(Server server) {
        Pane pane = server.newSession(session -> session.named("not-a-shell").running("sleep", "60"))
                .windows()
                .get(0)
                .panes()
                .get(0);

        assertThrows(IllegalStateException.class, () -> pane.run("true", GENEROUS));
    }
}
