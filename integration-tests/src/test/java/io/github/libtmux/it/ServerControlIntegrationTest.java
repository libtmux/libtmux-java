package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.ObjectDoesNotExistException;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.Session;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.UnsupportedTmuxVersionException;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.junit5.TmuxExtension;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Deciding inside tmux, locking, and reading what the server has been told.
 *
 * <p>{@code if-shell}, {@code lock-server} and {@code show-messages} declare the same flags from
 * 3.2a to 3.7b. The prompt-history commands do not exist at all before 3.3, which is a floor rather
 * than a flag, so both branches assert. The matrix has no plain-3.3 lane, so 3.2a and 3.3a are the
 * two lanes this floor is actually observed on; both take the same branch before and after 3.3 as
 * they did before, since neither is below the real floor.
 */
@ExtendWith(TmuxExtension.class)
final class ServerControlIntegrationTest {

    private static final TmuxVersion PROMPT_HISTORY_SINCE = new TmuxVersion(3, 3, "");

    /** Before 3.6, show-messages wants a client attached and refuses without one. */
    private static final TmuxVersion MESSAGES_WITHOUT_CLIENT_SINCE = new TmuxVersion(3, 6, "");

    /**
     * The choosing happens inside tmux: the condition and the outcome travel together, so nothing
     * can change between asking and acting.
     */
    @Test
    void aTrueConditionRunsTheCommandItGuards(Server server) throws Exception {
        Session session = server.sessions().get(0);

        server.shell().choose("true", "rename-window then-ran");

        assertTrue(
                Await.until(() ->
                        "then-ran".equals(session.refresh().windows().get(0).name())),
                "the guarded command never ran");
    }

    @Test
    void aFalseConditionRunsTheOtherOne(Server server) throws Exception {
        Session session = server.sessions().get(0);

        server.shell().choose("false", "rename-window then-ran", "rename-window else-ran");

        assertTrue(
                Await.until(() ->
                        "else-ran".equals(session.refresh().windows().get(0).name())),
                "the other command never ran");
    }

    @Test
    void aFalseConditionWithNoOtherCommandDoesNothing(Server server) {
        Session session = server.sessions().get(0);
        // An explicit name prevents shell startup from automatically renaming the window.
        String before = session.windows().get(0).rename("before-condition").name();

        server.shell().choose("false", "rename-window should-not-run");

        assertEquals(before, session.refresh().windows().get(0).name(), "something ran that should not have");
    }

    @Test
    void controlAttachesToTheCapturedServer(Server server) {
        Session session = server.sessions().get(0);

        try (ControlClient client = server.control(session)) {
            assertTrue(client.isAlive());
            assertEquals(
                    session.id().value(),
                    client.send("display-message", "-p", "#{session_id}")
                            .lines()
                            .get(0));
        }
    }

    /** A new server on the same socket reuses {@code $0}. The old handle must not attach to it. */
    @Test
    void controlRefusesAServerThatHasBeenReplaced(Server server) {
        Session session = server.sessions().get(0);
        ServerConfig config = server.config();
        server.killServer();

        try (Server replacement = Server.open(config)) {
            replacement.newSession("replacement");
            assertThrows(ObjectDoesNotExistException.class, () -> server.control(session));
            assertTrue(noClients(replacement), "a refused attach left a client behind");
        }
    }

    /**
     * The session id comes back with the next server. A client that was already attached has to end
     * with the old one, or its next command would be answered by the replacement.
     */
    @Test
    void anAttachedClientEndsWhenItsServerIsReplaced(Server server) {
        Session session = server.sessions().get(0);
        String id = session.id().value();
        ControlClient client = server.control(session);
        ServerConfig config = server.config();
        try {
            server.killServer();
            try (Server replacement = Server.open(config)) {
                Session again = replacement.newSession("replacement");
                assertEquals(id, again.id().value(), "the replacement did not reuse " + id);
                assertTrue(Await.until(() -> !client.isAlive()), "the client stayed up after its server was replaced");
                assertThrows(
                        RuntimeException.class,
                        () -> client.send(List.of("display-message", "-p", "#{session_name}"), Duration.ofMillis(500)));
                assertTrue(noClients(replacement), "the ended client was still attached to the replacement");
            }
        } finally {
            client.close();
        }
    }

    /** The check runs after the process is up, so a lie about the pid has to detach again. */
    @Test
    void controlDetachesWhenTheIncarnationDoesNotMatch(Server server) {
        Session session = server.sessions().get(0);

        assertThrows(
                ObjectDoesNotExistException.class,
                () -> ControlClient.attach(server.config(), session.id(), 1, "0.0", Duration.ofSeconds(5)));

        assertTrue(
                Await.until(() -> noClients(server)),
                "the control client stayed attached after the incarnation check failed");
    }

    private static boolean noClients(Server server) {
        return server.cmd("list-clients", "-F", "#{client_pid}").stdout().isEmpty();
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
            assertTrue(!server.messageLog().lines().isEmpty(), "a server that has been talked to has said something");
        } else {
            LibTmuxException refused = assertThrows(
                    LibTmuxException.class, () -> server.messageLog().lines());

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
            assertTrue(Await.until(() -> !server.clients().isEmpty()), "no client ever attached");

            assertTrue(!server.messageLog().lines().isEmpty(), "with a client attached the log is readable after all");
        }
    }

    // ------------------------------------------------------------------------------ prompt history

    /** 3.2a has no such command; from 3.3 it answers, empty until something has been typed. */
    @Test
    void thePromptHistoryIsReadableOrRefusedDependingOnTheRelease(Server server) {
        if (server.version().atLeast(PROMPT_HISTORY_SINCE)) {
            assertTrue(server.prompt().history() != null, "a readable history is a list, even when empty");
            server.prompt().clear();
            assertTrue(server.isAlive(), "clearing it is not a reason to lose the server");
        } else {
            UnsupportedTmuxVersionException refused = assertThrows(
                    UnsupportedTmuxVersionException.class, () -> server.prompt().history());

            assertTrue(
                    String.valueOf(refused.getMessage()).contains("3.3"),
                    "the refusal names the release that has it: " + refused.getMessage());
            assertThrows(
                    UnsupportedTmuxVersionException.class, () -> server.prompt().clear());
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
}
