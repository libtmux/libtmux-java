package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.BufferInfo;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Caller text that begins with a dash, at every place the library hands one to tmux as a positional
 * argument.
 *
 * <p>tmux parses arguments with getopt, so a value like {@code -R} is read as a flag unless {@code
 * --} ends the options first. Two things go wrong without it and only one of them is visible: an
 * unknown letter fails loudly, and a letter the command happens to accept does something else
 * entirely and reports success. {@code send-keys -R} resets the pane's terminal instead of typing,
 * and {@code set-hook -R} is a real flag too.
 *
 * <p>Each case runs against real tmux because that is the only thing that knows which letters each
 * command takes, and which release changed its mind.
 */
@ExtendWith(TmuxExtension.class)
final class LeadingDashIntegrationTest {

    /** A letter send-keys accepts as a flag, so the failure is silent rather than an error. */
    private static final String FLAGGISH = "-R";

    /** A letter no command here takes, so parsing it as one fails loudly. */
    private static final String UNKNOWN = "-Z";

    @Test
    void sendTypesTheCharactersRatherThanActingOnThem(Server server) throws InterruptedException {
        Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

        pane.send(FLAGGISH);

        // Read the pane rather than waiting on it: what was typed is this library's own echo, which
        // a wait discounts on purpose, so only a capture can confirm the characters arrived.
        assertTrue(
                Await.until(() -> pane.capture().stream().anyMatch(row -> row.contains(FLAGGISH))),
                "send-keys -R redraws the pane instead of typing, and reports success either way");
    }

    @Test
    void sendAcceptsALetterNoCommandTakes(Server server) throws InterruptedException {
        Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

        pane.send(UNKNOWN);

        assertTrue(Await.until(() -> pane.capture().stream().anyMatch(row -> row.contains(UNKNOWN))));
    }

    /**
     * The defect this pins: buffer contents were the one caller value tmux still read as flags.
     * {@code set("clip", "-nfoo")} renamed the buffer to {@code foo}, wrote nothing at all, and
     * reported success — the quietest shape this failure takes.
     */
    @Test
    void bufferContentsAreDataRatherThanFlags(Server server) {
        server.buffers().set("clip", "original");

        server.buffers().set("clip", "-nfoo");

        assertEquals("-nfoo", server.buffers().show("clip"), "the contents are what was written");
        assertEquals(
                List.of("clip"),
                server.buffers().list().stream().map(BufferInfo::name).toList(),
                "and -n was not read as a rename");
    }

    @Test
    void aSessionCanBeNamedWithALeadingDash(Server server) {
        Session renamed = server.sessions().get(0).rename(FLAGGISH);

        assertEquals(FLAGGISH, renamed.name());
    }

    @Test
    void aWindowCanBeNamedWithALeadingDash(Server server) {
        Window renamed = server.windows().get(0).rename(UNKNOWN);

        assertEquals(UNKNOWN, renamed.name());
    }

    @Test
    void aFormatCanBeginWithADash(Server server) {
        assertEquals(UNKNOWN, server.expand(UNKNOWN), "a format is text, and expands to itself when it holds no #{}");
    }

    @Test
    void aPaneFormatCanBeginWithADash(Server server) {
        Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

        assertEquals(UNKNOWN, pane.expand(UNKNOWN));
    }

    @Test
    void anOptionValueCanBeginWithADash(Server server) {
        server.globalOptions().set("@libtmux-probe", UNKNOWN);

        assertEquals(java.util.Optional.of(UNKNOWN), server.globalOptions().get("@libtmux-probe"));
    }

    @Test
    void aWaitChannelCanBeNamedWithALeadingDash(Server server) {
        // A signal with nobody waiting is remembered, so draining is the cheapest proof that the
        // wait addressed the same channel the signal did rather than reading either as a flag.
        server.channel(UNKNOWN).signal();

        assertTrue(server.channel(UNKNOWN).drain(), "the signal was stored under the name, so draining finds it");
    }

    /**
     * Nothing runs a program called {@code -Z} either way, so this compares the two forms rather
     * than reading tmux's words for the failure: with the terminator the argument reaches the shell
     * and the shell complains, without it tmux refuses to parse it and complains itself. The
     * wording of both changed across the supported range; that they differ did not.
     */
    @Test
    void aShellCommandCanBeginWithADash(Server server) {
        List<String> reachedTheShell =
                server.cmd(List.of("run-shell", "--", UNKNOWN)).stderr();
        List<String> refusedByTmux = server.cmd(List.of("run-shell", UNKNOWN)).stderr();

        assertNotEquals(
                refusedByTmux,
                reachedTheShell,
                "the terminator made no difference, so the argument never reached the shell");
    }

    /**
     * The escape hatch stays literal. {@code cmd} passes what it is given, so a caller building
     * their own argv keeps tmux's parsing — including the dash handling the typed methods take care
     * of. Pinned because the difference is deliberate.
     */
    @Test
    void theRawCommandEscapeHatchDoesNotInsertTheTerminator(Server server) {
        // tmux's own wording for a rejected flag changed across the supported range — 3.2a says
        // "unknown option -- Z" and 3.7 "unknown flag -Z" — so this asserts that it refused, which
        // is the fact, rather than how it said so.
        assertFalse(
                server.cmd("run-shell", UNKNOWN).succeeded(),
                "cmd is the way past the typed API, so it does not quietly rewrite the argv");
    }
}
