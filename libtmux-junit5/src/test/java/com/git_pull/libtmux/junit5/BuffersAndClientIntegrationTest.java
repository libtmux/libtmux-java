package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.BufferInfo;
import com.git_pull.libtmux.Client;
import com.git_pull.libtmux.ClientAttachment;
import com.git_pull.libtmux.ObjectDoesNotExist;
import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.control.ControlClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** The server's paste buffers, and what an attached client is looking at. */
@ExtendWith(TmuxExtension.class)
final class BuffersAndClientIntegrationTest {

    // -------------------------------------------------------------------------------- buffers

    @Test
    void aBufferRoundTripsThroughTheServer(Server server) {
        server.buffers().set("mine", "hello buffers");

        assertEquals("hello buffers", server.buffers().show("mine"));
        assertTrue(
                server.buffers().list().stream()
                        .anyMatch(buffer -> buffer.name().equals("mine")),
                "the buffer is in the listing");
    }

    @Test
    void aListingReportsEachBuffersSize(Server server) {
        server.buffers().set("sized", "12345");

        BufferInfo listed = server.buffers().list().stream()
                .filter(buffer -> buffer.name().equals("sized"))
                .findFirst()
                .orElseThrow();

        assertEquals(5, listed.size());
    }

    @Test
    void anEmptyStackIsAnEmptyListNotAFailure(Server server) {
        assertEquals(List.of(), server.buffers().list(), "a server with nothing copied has no buffers");
    }

    @Test
    void aBufferThatIsNotThereSaysSo(Server server) {
        assertThrows(ObjectDoesNotExist.class, () -> server.buffers().show("never-set"));
    }

    @Test
    void deletingRemovesItFromTheListing(Server server) {
        server.buffers().set("doomed", "x");

        server.buffers().delete("doomed");

        assertFalse(server.buffers().list().stream()
                .anyMatch(buffer -> buffer.name().equals("doomed")));
    }

    @Test
    void aBufferSurvivesAFileRoundTrip(Server server, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("buffer.txt");
        server.buffers().set("saved", "written to disk");

        server.buffers().save("saved", file);
        server.buffers().load("reloaded", file);

        assertEquals("written to disk", Files.readString(file).stripTrailing());
        assertEquals("written to disk", server.buffers().show("reloaded"));
    }

    @Test
    void pastingPutsABufferIntoAPane(Server server) throws Exception {
        Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);
        server.buffers().set("typed", "echo pasted-this\n");

        pane.paste("typed");

        assertTrue(
                await(() -> pane.capture().stream().anyMatch(line -> line.contains("pasted-this"))),
                "the buffer never reached the pane");
    }

    @Test
    void sourcingAFileRunsTheCommandsInIt(Server server, @TempDir Path directory) throws Exception {
        Path script = directory.resolve("commands.conf");
        Files.writeString(script, "new-window -d -n from-a-file\n");

        server.sourceFile(script);

        assertTrue(server.windows().stream().anyMatch(window -> window.name().equals("from-a-file")));
    }

    // --------------------------------------------------------------------------------- client

    /**
     * The fixture session is detached, so a client has to be made. A control client attaches, which
     * is what gives tmux a client to report at all.
     */
    @Test
    void anAttachedClientReportsWhatItIsLookingAt(Server server) throws Exception {
        Session session = server.sessions().get(0);
        try (ControlClient attached = ControlClient.attach(server.config(), session.id())) {
            assertTrue(await(() -> !server.clients().isEmpty()), "the control client never appeared as a client");

            Client client = server.clients().get(0);
            ClientAttachment looking = client.attachment().orElseThrow();

            assertEquals(session.id(), looking.session().id());
            assertEquals(
                    looking.session().activeWindow().orElseThrow().id(),
                    looking.activeWindow().id(),
                    "the window it reports is the session's active one");
            assertTrue(looking.activePane().active(), "and the pane is that window's active pane");
            assertEquals(attached.send("display-message", "-p", "ok").lines(), List.of("ok"));
        }
    }

    @Test
    void fetchingAnAttachmentTakesAFreshLook(Server server) throws Exception {
        Session session = server.sessions().get(0);
        try (ControlClient attached = ControlClient.attach(server.config(), session.id())) {
            assertTrue(attached.send("display-message", "-p", "ready").succeeded());
            assertTrue(await(() -> !server.clients().isEmpty()));
            Client client = server.clients().get(0);
            String before = client.attachment().orElseThrow().activeWindow().name();

            session.newWindow("appeared-after");

            assertEquals(
                    before, client.attachment().orElseThrow().activeWindow().name(), "the capture is a moment");
            assertEquals(
                    "appeared-after",
                    client.fetchAttachment().orElseThrow().activeWindow().name(),
                    "and fetching looks again");
        }
    }

    @Test
    void aClientThatHasGoneRefreshesToNothing(Server server) throws Exception {
        Session session = server.sessions().get(0);
        // Whichever clients are already here belong to somebody else — a control carrier attaches
        // one of its own to carry commands at all. The client under test is the one that appears.
        java.util.Set<String> before =
                server.clients().stream().map(Client::name).collect(java.util.stream.Collectors.toSet());

        Client client;
        try (ControlClient attached = ControlClient.attach(server.config(), session.id())) {
            assertTrue(attached.send("display-message", "-p", "ready").succeeded());
            assertTrue(await(() -> appeared(server, before).isPresent()), "no client ever attached");
            client = appeared(server, before).orElseThrow();
        }

        assertTrue(await(() -> client.refresh().isEmpty()), "the client outlived the connection that made it");
        assertEquals(java.util.Optional.empty(), client.fetchAttachment());
    }

    /** The client that attached after the named ones were already there. */
    private static java.util.Optional<Client> appeared(Server server, java.util.Set<String> before) {
        return server.clients().stream()
                .filter(client -> !before.contains(client.name()))
                .findFirst();
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
