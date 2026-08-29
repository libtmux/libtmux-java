package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Server;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TmuxExtension.class)
final class TypingTest {

    private static final TmuxVersion SAFE_PASTE_CLEANUP = new TmuxVersion(3, 4, "");

    @Test
    void keysAreSentByNameSoAnInterruptInterrupts(Server server) {
        String pane = server.panes().get(0).id().value();
        server.run(List.of("send-keys", "-l", "-t", pane, "sleep 60"));
        server.run(List.of("send-keys", "-t", pane, "Enter"));

        Typing.Sent sent = Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("C-c")));

        assertEquals(1, sent.keys());
        assertFalse(sent.literal());
        assertTrue(String.valueOf(sent.note()).contains("not waited for"), String.valueOf(sent.note()));
    }

    @Test
    void sendingNoKeysAtAllSaysWhatWasWanted(Server server) {
        String pane = server.panes().get(0).id().value();

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of())));

        assertTrue(String.valueOf(refused.getMessage()).contains("C-c"), refused.getMessage());
    }

    @Test
    void pastedTextArrivesWithoutClaimingAUsersBuffer(Server server) {
        assumeTrue(server.version().atLeast(SAFE_PASTE_CLEANUP));
        String pane = server.panes().get(0).id().value();
        server.buffers().set("libtmux-mcp-paste", "user-owned");

        Typing.Pasted pasted = Typing.pasteText(TestCalls.on(server, "pane_id", pane, "text", "Enter [C-c] done"));

        assertEquals(16, pasted.characters());
        assertTrue(String.valueOf(pasted.note()).contains("pass 'enter'"), String.valueOf(pasted.note()));
        assertEquals("user-owned", server.buffers().show("libtmux-mcp-paste"));
        assertNoOwnedBuffers(server);
    }

    @Test
    void pasteRefusesUnsafeCleanupBeforeCreatingABuffer(Server server) {
        assumeFalse(server.version().atLeast(SAFE_PASTE_CLEANUP));
        String pane = server.panes().get(0).id().value();
        server.buffers().set("libtmux-mcp-paste", "user-owned");

        LibTmuxException refused = assertThrows(
                LibTmuxException.class,
                () -> Typing.pasteText(TestCalls.on(server, "pane_id", pane, "text", "must-not-be-buffered")));

        assertTrue(String.valueOf(refused.getMessage()).contains("requires tmux 3.4"), refused.getMessage());
        assertEquals("user-owned", server.buffers().show("libtmux-mcp-paste"));
        assertNoOwnedBuffers(server);
    }

    @Test
    void failedPasteDeletesOnlyItsOwnedBuffer(Server server) throws Exception {
        assumeTrue(server.version().atLeast(SAFE_PASTE_CLEANUP));
        String pane = server.panes().get(0).id().value();
        server.buffers().set("libtmux-mcp-paste", "user-owned");
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport failingPaste = new TmuxTransport() {
                @Override
                public CommandResult execute(CommandRequest request) {
                    if (request.argv().get(0).equals("paste-buffer")) {
                        return new CommandResult(1, List.of(), List.of("forced paste failure"));
                    }
                    return processes.execute(request);
                }

                @Override
                public void close() {}
            };
            try (Server measured = Server.using(server.config(), failingPaste)) {
                assertThrows(
                        LibTmuxException.class,
                        () -> Typing.pasteText(TestCalls.on(measured, "pane_id", pane, "text", "created-first")));
            }
        }

        assertEquals("user-owned", server.buffers().show("libtmux-mcp-paste"));
        assertNoOwnedBuffers(server);
    }

    @Test
    void concurrentPastesDoNotShareTheirServerGlobalBuffer(Server server) throws Exception {
        assumeTrue(server.version().atLeast(SAFE_PASTE_CLEANUP));
        String firstPane = server.panes().get(0).id().value();
        String secondPane = server.panes().get(0).split().id().value();
        Map<String, String> contentsByBuffer = new ConcurrentHashMap<>();
        Map<String, String> bufferByTarget = new ConcurrentHashMap<>();
        CountDownLatch bothBuffersSet = new CountDownLatch(2);
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport interleaving = new TmuxTransport() {
                @Override
                public CommandResult execute(CommandRequest request) {
                    CommandResult result = processes.execute(request);
                    if (request.argv().get(0).equals("set-buffer")) {
                        contentsByBuffer.put(
                                request.argv().get(2), request.argv().get(3));
                        bothBuffersSet.countDown();
                        await(bothBuffersSet);
                    } else if (request.argv().get(0).equals("paste-buffer")) {
                        bufferByTarget.put(request.argv().get(5), request.argv().get(3));
                    }
                    return result;
                }

                @Override
                public void close() {}
            };
            try (Server measured = Server.using(server.config(), interleaving);
                    var calls = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = calls.submit(() ->
                        Typing.pasteText(TestCalls.on(measured, "pane_id", firstPane, "text", "first-paste-marker")));
                var second = calls.submit(() ->
                        Typing.pasteText(TestCalls.on(measured, "pane_id", secondPane, "text", "second-paste-marker")));

                assertEquals(firstPane, first.get(10, TimeUnit.SECONDS).paneId());
                assertEquals(secondPane, second.get(10, TimeUnit.SECONDS).paneId());
            }
        }

        assertEquals("first-paste-marker", contentsByBuffer.get(bufferByTarget.get(firstPane)));
        assertEquals("second-paste-marker", contentsByBuffer.get(bufferByTarget.get(secondPane)));
        assertNoOwnedBuffers(server);
    }

    private static void assertNoOwnedBuffers(Server server) {
        assertTrue(server.buffers().list().stream()
                .noneMatch(buffer -> buffer.name().startsWith("libtmux-mcp-paste-")));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out arranging concurrent pastes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while arranging concurrent pastes", e);
        }
    }
}
