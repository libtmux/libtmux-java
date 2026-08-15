package com.git_pull.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.LibTmuxException;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.TmuxVersion;
import com.git_pull.libtmux.UnsupportedTmuxVersion;
import com.git_pull.libtmux.control.ControlClient;
import com.git_pull.libtmux.junit5.TmuxExtension;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Deciding inside tmux, locking, and reading what the server has been told.
 *
 * <p>{@code if-shell}, {@code lock-server} and {@code show-messages} declare the same flags from
 * 3.2a to 3.7b. The prompt-history commands do not exist at all before 3.3a, which is a floor rather
 * than a flag, so both branches assert.
 */
@ExtendWith(TmuxExtension.class)
final class ServerControlIntegrationTest {

    private static final TmuxVersion PROMPT_HISTORY_SINCE = new TmuxVersion(3, 3, "a");

    /** Before 3.6, show-messages wants a client attached and refuses without one. */
    private static final TmuxVersion MESSAGES_WITHOUT_CLIENT_SINCE = new TmuxVersion(3, 6, "");

    /**
     * The choosing happens inside tmux: the condition and the outcome travel together, so nothing
     * can change between asking and acting.
     */
    @Test
    void aTrueConditionRunsTheCommandItGuards(Server server) throws Exception {
        Session session = server.sessions().get(0);

        server.ifShell("true", "rename-window then-ran");

        assertTrue(
                await(() -> "then-ran".equals(session.refresh().windows().get(0).name())),
                "the guarded command never ran");
    }

    @Test
    void aFalseConditionRunsTheOtherOne(Server server) throws Exception {
        Session session = server.sessions().get(0);

        server.ifShell("false", "rename-window then-ran", "rename-window else-ran");

        assertTrue(
                await(() -> "else-ran".equals(session.refresh().windows().get(0).name())),
                "the other command never ran");
    }

    @Test
    void aFalseConditionWithNoOtherCommandDoesNothing(Server server) throws Exception {
        Session session = server.sessions().get(0);
        String before = session.windows().get(0).name();

        server.ifShell("false", "rename-window should-not-run");
        Thread.sleep(400);

        assertEquals(before, session.refresh().windows().get(0).name(), "something ran that should not have");
    }

    @Test
    void lockingIsAcceptedEvenWithNobodyAttached(Server server) {
        server.lock();

        assertTrue(server.isAlive(), "locking is not a reason to lose the server");
    }

    /**
     * Readable detached from 3.6; before that tmux wants a client and says so. Not gated on the
     * version, because a gate would refuse the case below that works.
     */
    @Test
    void theMessageLogIsReadableDetachedFromThirtySixOnwards(Server server) {
        if (server.version().atLeast(MESSAGES_WITHOUT_CLIENT_SINCE)) {
            assertTrue(!server.messages().isEmpty(), "a server that has been talked to has said something");
        } else {
            LibTmuxException refused = assertThrows(LibTmuxException.class, server::messages);

            assertTrue(
                    String.valueOf(refused.getMessage()).contains("no current client"),
                    "tmux says what is missing: " + refused.getMessage());
        }
    }

    /** The reason it is not gated: attach a client and the older releases answer too. */
    @Test
    void anOlderReleaseAnswersOnceAClientIsAttached(Server server) throws Exception {
        if (server.version().atLeast(MESSAGES_WITHOUT_CLIENT_SINCE)) {
            return; // nothing to prove; it already answers without one
        }
        Session session = server.sessions().get(0);

        try (ControlClient attached = ControlClient.attach(server.config(), session.id())) {
            assertTrue(attached.send("display-message", "-p", "ready").succeeded());
            assertTrue(await(() -> !server.clients().isEmpty()), "no client ever attached");

            assertTrue(!server.messages().isEmpty(), "with a client attached the log is readable after all");
        }
    }

    // ------------------------------------------------------------------------------ prompt history

    /** 3.2a has no such command; from 3.3a it answers, empty until something has been typed. */
    @Test
    void thePromptHistoryIsReadableOrRefusedDependingOnTheRelease(Server server) {
        if (server.version().atLeast(PROMPT_HISTORY_SINCE)) {
            assertTrue(server.promptHistory() != null, "a readable history is a list, even when empty");
            server.clearPromptHistory();
            assertTrue(server.isAlive(), "clearing it is not a reason to lose the server");
        } else {
            UnsupportedTmuxVersion refused = assertThrows(UnsupportedTmuxVersion.class, server::promptHistory);

            assertTrue(
                    String.valueOf(refused.getMessage()).contains("3.3a"),
                    "the refusal names the release that has it: " + refused.getMessage());
            assertThrows(UnsupportedTmuxVersion.class, server::clearPromptHistory);
        }
    }

    /** The floor is exactly 3.2a, and every other lane has to take the working path. */
    @Test
    void exactlyTheOldestReleaseRefuses(Server server) {
        String lane = System.getProperty("libtmux.tmux.expected");
        if (lane == null) {
            return; // not a matrix lane; the ordinary suite runs whichever tmux is on PATH
        }

        assertEquals(
                "3.2a".equals(lane),
                !server.version().atLeast(PROMPT_HISTORY_SINCE),
                "lane " + lane + " disagrees with the version rule");
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
