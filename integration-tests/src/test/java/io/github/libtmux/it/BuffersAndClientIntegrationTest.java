package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.BufferInfo;
import io.github.libtmux.Client;
import io.github.libtmux.ClientAttachment;
import io.github.libtmux.LibTmuxException;
import io.github.libtmux.ObjectDoesNotExist;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.UnsupportedTmuxVersion;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.junit5.TmuxExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** The server's paste buffers, and what an attached client is looking at. */
@ExtendWith(TmuxExtension.class)
final class BuffersAndClientIntegrationTest {

    private static final TmuxVersion EXACT_NAMED_DELETE = new TmuxVersion(3, 4, "");

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
    void aTrailingSemicolonIsPartOfTheBufferName(Server server) {
        server.buffers().set("literal;", "not a command separator;");

        assertEquals("not a command separator;", server.buffers().show("literal;"));
        assertTrue(server.buffers().list().stream()
                .anyMatch(buffer -> buffer.name().equals("literal;")));
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
        server.buffers().set("doomed;", "x");

        if (!server.version().atLeast(EXACT_NAMED_DELETE)) {
            assertThrows(UnsupportedTmuxVersion.class, () -> server.buffers().delete("doomed;"));
            assertEquals("x", server.buffers().show("doomed;"), "refusal leaves the buffer untouched");
            return;
        }

        server.buffers().delete("doomed;");

        assertEquals(List.of(), server.buffers().list());
    }

    @Test
    void deletingAnAbsentBufferDoesNotDeleteTheTopBuffer(Server server) {
        server.buffers().set("belongs-to-the-user", "keep me");

        if (server.version().atLeast(EXACT_NAMED_DELETE)) {
            assertThrows(ObjectDoesNotExist.class, () -> server.buffers().delete("never-set;"));
        } else {
            assertThrows(UnsupportedTmuxVersion.class, () -> server.buffers().delete("never-set;"));
        }

        assertEquals("keep me", server.buffers().show("belongs-to-the-user"));
        assertEquals(
                List.of("belongs-to-the-user"),
                server.buffers().list().stream().map(BufferInfo::name).toList(),
                "only the user's buffer remains");
    }

    @Test
    void aBufferSurvivesAFileRoundTrip(Server server, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("buffer;");
        server.buffers().set("saved;", "written to disk");

        server.buffers().save("saved;", file);
        server.buffers().load("reloaded;", file);

        assertEquals("written to disk", Files.readString(file).stripTrailing());
        assertEquals("written to disk", server.buffers().show("reloaded;"));
    }

    @Test
    void pastingPutsABufferIntoAPane(Server server) throws Exception {
        Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);
        server.buffers().set("typed", "echo pasted-this\n");

        pane.pasteBuffer("typed");

        assertTrue(
                await(() -> pane.capture().stream().anyMatch(line -> line.contains("pasted-this"))),
                "the buffer never reached the pane");
    }

    @Test
    void pastingTextLeavesNothingInTheBufferStack(Server server) throws Exception {
        Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);
        server.buffers().set("belongs-to-the-user", "keep me");

        if (!server.version().atLeast(EXACT_NAMED_DELETE)) {
            assertThrows(UnsupportedTmuxVersion.class, () -> pane.paste("echo pasted-text\n"));
            assertEquals(
                    List.of("belongs-to-the-user"),
                    server.buffers().list().stream().map(BufferInfo::name).toList(),
                    "refusal creates no buffer");
            return;
        }

        pane.paste("echo pasted-text\n");

        assertTrue(
                await(() -> pane.capture().stream().anyMatch(line -> line.contains("pasted-text"))),
                "the text never reached the pane");
        assertEquals(
                List.of("belongs-to-the-user"),
                server.buffers().list().stream().map(BufferInfo::name).toList(),
                "the paste kept no buffer of its own");
    }

    /** The one case where the group's own cleanup cannot run, so the caller's has to. */
    @Test
    void aPasteThatFailsRemovesOnlyTheBufferItMade(Server server) {
        Pane doomed = server.sessions().get(0).windows().get(0).panes().get(0).split();
        server.buffers().set("belongs-to-the-user", "keep me");
        server.cmd("kill-pane", "-t", doomed.id().value());

        if (!server.version().atLeast(EXACT_NAMED_DELETE)) {
            assertThrows(UnsupportedTmuxVersion.class, () -> doomed.paste("never-arrives"));
        } else {
            assertThrows(LibTmuxException.class, () -> doomed.paste("never-arrives"));
        }

        assertEquals(
                List.of("belongs-to-the-user"),
                server.buffers().list().stream().map(BufferInfo::name).toList(),
                "a failed paste left its own buffer behind");
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
        Set<String> before = server.clients().stream().map(Client::name).collect(Collectors.toSet());

        Client client;
        try (ControlClient attached = ControlClient.attach(server.config(), session.id())) {
            assertTrue(attached.send("display-message", "-p", "ready").succeeded());
            assertTrue(await(() -> appeared(server, before).isPresent()), "no client ever attached");
            client = appeared(server, before).orElseThrow();
        }

        assertTrue(await(() -> client.refresh().isEmpty()), "the client outlived the connection that made it");
        assertEquals(Optional.empty(), client.fetchAttachment());
    }

    /** The client that attached after the named ones were already there. */
    private static Optional<Client> appeared(Server server, Set<String> before) {
        return server.clients().stream()
                .filter(client -> !before.contains(client.name()))
                .findFirst();
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
