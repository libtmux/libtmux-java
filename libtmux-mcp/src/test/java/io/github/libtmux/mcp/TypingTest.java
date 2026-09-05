package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Server;
import io.github.libtmux.SplitSpec;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
        assertEquals(List.of(pane), sent.resolvedPaneIds());
        assertTrue(String.valueOf(sent.note()).contains("not waited for"), String.valueOf(sent.note()));
    }

    @Test
    void synchronizedInputDisclosesEveryResolvedPane(Server server) {
        var source = server.panes().getFirst();
        var other = source.split(SplitSpec.builder().build());
        source.window().setSynchronizePanes(true);

        Typing.Sent sent = Typing.sendKeys(
                TestCalls.on(server, "pane_id", source.id().value(), "keys", List.of("q"), "literal", true));

        assertEquals(Set.of(source.id().value(), other.id().value()), Set.copyOf(sent.resolvedPaneIds()));
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
        server.buffers().set("libtmux-paste", "user-owned");

        Typing.Pasted pasted = Typing.pasteText(TestCalls.on(server, "pane_id", pane, "text", "Enter [C-c] done"));

        assertEquals(16, pasted.characters());
        assertTrue(String.valueOf(pasted.note()).contains("pass 'enter'"), String.valueOf(pasted.note()));
        assertEquals("user-owned", server.buffers().show("libtmux-paste"));
        assertNoOwnedBuffers(server);
    }

    @Test
    void pasteRefusesUnsafeCleanupBeforeCreatingABuffer(Server server) {
        assumeFalse(server.version().atLeast(SAFE_PASTE_CLEANUP));
        String pane = server.panes().get(0).id().value();
        server.buffers().set("libtmux-paste", "user-owned");

        LibTmuxException refused = assertThrows(
                LibTmuxException.class,
                () -> Typing.pasteText(TestCalls.on(server, "pane_id", pane, "text", "must-not-be-buffered")));

        assertTrue(String.valueOf(refused.getMessage()).contains("requires tmux 3.4"), refused.getMessage());
        assertEquals("user-owned", server.buffers().show("libtmux-paste"));
        assertNoOwnedBuffers(server);
    }

    /** A client that goes away mid-paste is the case that decides whether the text can outlive it. */
    @Test
    void aDisconnectDuringAPasteLeavesNothingOnTheServer(Server server) {
        assumeTrue(server.version().atLeast(SAFE_PASTE_CLEANUP));
        String pane = server.panes().get(0).id().value();
        try (ProcessTransport processes = new ProcessTransport()) {
            AtomicReference<Server> pasting = new AtomicReference<>();
            TmuxTransport disconnecting = new TmuxTransport() {
                @Override
                public CommandResult execute(CommandRequest request) {
                    CommandResult result = processes.execute(request);
                    if (String.join(" ", request.commands().get(0)).contains("set-buffer")) {
                        pasting.get().close();
                    }
                    return result;
                }

                @Override
                public void close() {}
            };
            Server cut = Server.using(server.config(), disconnecting);
            pasting.set(cut);
            try {
                Typing.pasteText(TestCalls.on(cut, "pane_id", pane, "text", "secret-text"));
            } catch (RuntimeException expected) {
                // The disconnect is what this arranges; surviving it is not what is being asserted.
            }
        }

        assertNoOwnedBuffers(server);
        assertTrue(
                String.join("\n", server.cmd("capture-pane", "-p", "-t", pane).stdout())
                        .contains("secret-text"));
    }

    @Test
    void eachConcurrentPasteReachesOnlyItsOwnPane(Server server) throws Exception {
        assumeTrue(server.version().atLeast(SAFE_PASTE_CLEANUP));
        String firstPane = server.panes().get(0).id().value();
        String secondPane = server.panes().get(0).split().id().value();
        CountDownLatch bothPending = new CountDownLatch(2);
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport interleaving = new TmuxTransport() {
                @Override
                public CommandResult execute(CommandRequest request) {
                    if (String.join(" ", request.commands().get(0)).contains("paste-buffer")) {
                        // Hold both pastes open at once, so a shared buffer name would collide.
                        bothPending.countDown();
                        await(bothPending);
                    }
                    return processes.execute(request);
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

        assertTrue(captureOf(server, firstPane).contains("first-paste-marker"));
        assertTrue(captureOf(server, secondPane).contains("second-paste-marker"));
        assertNoOwnedBuffers(server);
    }

    private static String captureOf(Server server, String pane) {
        return String.join("\n", server.cmd("capture-pane", "-p", "-t", pane).stdout());
    }

    private static void assertNoOwnedBuffers(Server server) {
        assertTrue(server.buffers().list().stream()
                .noneMatch(buffer -> buffer.name().startsWith("libtmux-paste-")));
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
