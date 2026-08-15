package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Client;
import com.git_pull.libtmux.LibTmuxException;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.control.ControlClient;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What can be done to an attached client, and ending a session by name.
 *
 * <p>Only the flags that held still across the whole range are used here. {@code switch-client}
 * gained {@code -O} in 3.7b, {@code refresh-client} moved twice, and {@code kill-session} gained
 * {@code -g} in 3.7b; none of those is reached, so nothing here needs a version rule.
 */
@ExtendWith(TmuxExtension.class)
final class ClientOperationsIntegrationTest {

    @Test
    void aClientCanBeDetachedAndTheSessionSurvives(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient attached = ControlClient.attach(server.config(), session.id())) {
            assertTrue(attached.send("display-message", "-p", "ready").succeeded());
            assertTrue(await(() -> !server.clients().isEmpty()), "no client ever attached");
            Client client = server.clients().get(0);

            client.detach();

            assertTrue(await(() -> server.clients().isEmpty()), "the client is still attached");
            assertTrue(server.isAlive(), "detaching is not killing");
            assertTrue(
                    server.sessions().stream().anyMatch(seen -> seen.id().equals(session.id())),
                    "the session outlived the client");
        }
    }

    @Test
    void aClientCanBeMovedToAnotherSession(Server server) throws Exception {
        Session first = server.sessions().get(0);
        Session second = server.newSession(s -> s.named("elsewhere"));

        try (ControlClient attached = ControlClient.attach(server.config(), first.id())) {
            assertTrue(attached.send("display-message", "-p", "ready").succeeded());
            assertTrue(await(() -> !server.clients().isEmpty()));
            Client client = server.clients().get(0);

            client.switchTo(second);

            assertTrue(
                    await(() -> server.clients().stream()
                            .findFirst()
                            .flatMap(Client::fetchAttachment)
                            .map(seen -> seen.session().id().equals(second.id()))
                            .orElse(false)),
                    "the client is not looking at the session it was switched to");
        }
    }

    @Test
    void redrawingIsNotTheSameAsRecapturing(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient attached = ControlClient.attach(server.config(), session.id())) {
            assertTrue(attached.send("display-message", "-p", "ready").succeeded());
            assertTrue(await(() -> !server.clients().isEmpty()));
            Client client = server.clients().get(0);

            client.redraw();

            assertTrue(client.refresh().isPresent(), "the client is still there afterwards");
        }
    }

    @Test
    void detachingEveryOtherClientLeavesThisOneAttached(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient one = ControlClient.attach(server.config(), session.id());
                ControlClient two = ControlClient.attach(server.config(), session.id())) {
            assertTrue(one.send("display-message", "-p", "ready").succeeded());
            assertTrue(two.send("display-message", "-p", "ready").succeeded());
            assertTrue(await(() -> server.clients().size() >= 2), "two clients never attached");
            Client survivor = server.clients().get(0);

            survivor.detachOthers();

            assertTrue(await(() -> server.clients().size() == 1), "the others are still attached");
            assertEquals(survivor.name(), server.clients().get(0).name(), "and the survivor is the one that asked");
        }
    }

    // ------------------------------------------------------------------------- killing by name

    @Test
    void aSessionCanBeEndedByName(Server server) {
        server.newSession(s -> s.named("doomed"));
        assertTrue(server.hasSession("doomed"));

        server.killSession("doomed");

        assertFalse(server.hasSession("doomed"));
    }

    /** tmux matches a target as a prefix unless told otherwise, which would kill the wrong session. */
    @Test
    void killingByNameDoesNotTakeASessionThatMerelyStartsTheSame(Server server) {
        server.newSession(s -> s.named("build-cache"));
        server.newSession(s -> s.named("build"));

        server.killSession("build");

        assertFalse(server.hasSession("build"));
        assertTrue(server.hasSession("build-cache"), "a prefix match would have taken this one too");
    }

    @Test
    void killingASessionThatIsNotThereSaysSo(Server server) {
        assertThrows(LibTmuxException.class, () -> server.killSession("never-existed"));
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
