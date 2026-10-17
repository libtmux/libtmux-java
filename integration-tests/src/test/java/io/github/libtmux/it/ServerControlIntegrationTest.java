package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.Session;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.exception.TargetGoneException;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.ProcessTransport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Deciding inside tmux, locking, and reading what the server has been told.
 *
 * <p>{@code if-shell}, {@code lock-server} and {@code show-messages} declare the same flags on every
 * supported release. The prompt-history commands do not exist at all before 3.3, which is a floor rather
 * than a flag, so both branches assert: 3.2a takes the refusing one and every later release the
 * working one.
 */
@ExtendWith(TmuxExtension.class)
final class ServerControlIntegrationTest {

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
    @Tag("control")
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
    @Tag("control")
    void controlRefusesAServerThatHasBeenReplaced(Server server) {
        Session session = server.sessions().get(0);
        ServerConfig config = server.config();
        server.killServer();

        try (Server replacement = Server.open(config)) {
            replacement.newSession("replacement");
            assertThrows(TargetGoneException.class, () -> server.control(session));
            assertTrue(noClients(replacement), "a refused attach left a client behind");
        }
    }

    /**
     * The session id comes back with the next server. A client that was already attached has to end
     * with the old one, or its next command would be answered by the replacement.
     */
    @Test
    @Tag("control")
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

    /**
     * tmux answers {@code kill-server} before its daemon exits, and the daemon keeps its socket
     * until every client has gone, so a server started straight afterwards could reach the old one.
     */
    @Test
    void killingTheServerWaitsForItsDaemonToExit(Server server) throws Exception {
        long pid = Long.parseLong(server.expand("#{pid}"));
        NativeClient client = NativeClient.attach(server.sessions().get(0));
        try {
            server.killServer();

            assertFalse(
                    ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    () -> "daemon " + pid + " " + ProcessHandle.of(pid).map(ProcessHandle::info) + " " + stat(pid));
        } finally {
            client.close();
        }
    }

    /** A captured pid mismatch is refused before control acquisition. */
    @Test
    @Tag("control")
    void controlDetachesWhenTheIncarnationDoesNotMatch(Server server) {
        Session session = server.sessions().get(0);

        assertThrows(
                TargetGoneException.class,
                () -> ControlClient.attach(server.config(), session.id(), 1, "0.0", Duration.ofSeconds(5)));

        assertTrue(Await.until(() -> noClients(server)), "the refused identity left an attachment");
    }

    /**
     * The live pid and version, and the start time of a server that is not this one: what a tmux
     * restarted on its old pid shows a capture from before the restart.
     */
    @Test
    @Tag("control")
    void controlRefusesAServerStartedAtAnotherTime(Server server) {
        Session session = server.sessions().get(0);
        var captured = server.snapshot();
        String version =
                server.cmd("display-message", "-p", "#{version}").stdout().get(0);

        try (ProcessTransport transport = new ProcessTransport()) {
            assertThrows(
                    TargetGoneException.class,
                    () -> ControlClient.attach(
                            transport.controlCarrier().orElseThrow(),
                            server.config(),
                            session.id(),
                            captured.serverPid().orElseThrow(),
                            java.util.OptionalLong.of(captured.serverStartTime().orElseThrow() - 1),
                            version,
                            Duration.ofSeconds(5)));
        }

        assertTrue(
                Await.until(() -> noClients(server)),
                "the control client stayed attached after the start time did not match");
    }

    private static String stat(long pid) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("/proc/" + pid + "/stat"));
        } catch (java.io.IOException unreadable) {
            return unreadable.toString();
        }
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

    @Test
    void theMessageLogIsReadableWithAnOrdinaryClientAttached(Server server) throws Exception {
        try (NativeClient attached = NativeClient.attach(server.sessions().getFirst())) {
            assertEquals(
                    List.of("0"),
                    server.cmd("list-clients", "-F", "#{client_control_mode}").stdout());
            attached.run("display-message", "ordinary-client-log-marker");
            assertTrue(
                    server.messageLog().lines().stream().anyMatch(line -> line.contains("ordinary-client-log-marker")),
                    "an ordinary client's message must remain readable");
        }
        assertTrue(Await.until(() -> noClients(server)), "closing the PTY left an attached client");
        assertTrue(server.isAlive(), "closing a client must preserve its server");
    }

    // ------------------------------------------------------------------------------ prompt history

    @Test
    void thePromptHistoryIsReadableAndClearable(Server server) {
        assertTrue(server.prompt().history() != null, "a readable history is a list, even when empty");
        server.prompt().clear();
        assertTrue(server.isAlive(), "clearing it must keep the server alive");
    }
}
