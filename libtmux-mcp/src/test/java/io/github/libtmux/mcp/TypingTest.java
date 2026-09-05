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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
    void leadingOptionNamesReachTheSingleSendRoute(Server server) throws Exception {
        String pane = server.panes().getFirst().id().value();
        List<String> input = List.of("-X", "-R", "-N");

        Typing.Sent sent = Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", input, "literal", true));

        assertEquals(3, sent.keys());
        assertTrue(await(() -> captureOf(server, pane).contains("-X-R-N")));
    }

    @Test
    void leadingOptionNamesReachEveryBatchRoute(Server server) throws Exception {
        var first = server.panes().getFirst();
        var second = first.split(SplitSpec.builder().build());
        var third = first.split(SplitSpec.builder().build());
        List<Map<String, Object>> operations = List.of(
                send(first.id().value(), "-X"),
                send(second.id().value(), "-R"),
                send(third.id().value(), "-N"));

        List<Map<String, Object>> rows = rows(
                map(Operations.sendKeysBatch(TestCalls.on(server, "operations", operations, "onError", "continue"))));

        assertEquals(3, rows.size());
        assertTrue(rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("success"))), rows.toString());
        assertTrue(await(() -> captureOf(server, first.id().value()).contains("-X")));
        assertTrue(await(() -> captureOf(server, second.id().value()).contains("-R")));
        assertTrue(await(() -> captureOf(server, third.id().value()).contains("-N")));
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
    void callerPaneRefusesDirectKeys(Server server) {
        String pane = server.panes().getFirst().id().value();
        String marker = "caller-direct-marker";

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> Typing.sendKeys(
                        TestCalls.asCaller(server, pane, "pane_id", pane, "keys", List.of(marker), "literal", true)));

        assertTrue(String.valueOf(refused.getMessage()).contains(pane), refused.getMessage());
        assertFalse(captureOf(server, pane).contains(marker));
    }

    @Test
    void callerPaneRefusesPaste(Server server) {
        assumeTrue(server.version().atLeast(SAFE_PASTE_CLEANUP));
        String pane = server.panes().getFirst().id().value();
        String marker = "caller-paste-marker";

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> Typing.pasteText(TestCalls.asCaller(server, pane, "pane_id", pane, "text", marker)));

        assertTrue(String.valueOf(refused.getMessage()).contains(pane), refused.getMessage());
        assertFalse(captureOf(server, pane).contains(marker));
        assertNoOwnedBuffers(server);
    }

    @Test
    void batchProtectsACallerPeerInTheConfiguredCohort(Server server) {
        var source = server.panes().getFirst();
        var peer = source.split(SplitSpec.builder().build());
        source.window().setSynchronizePanes(true);
        String marker = "caller-batch-peer-marker";

        Map<String, Object> batch = map(Operations.sendKeysBatch(TestCalls.asCaller(
                server,
                peer.id().value(),
                "operations",
                List.of(send(source.id().value(), marker)))));
        Map<String, Object> row = rows(batch).getFirst();

        assertEquals(false, row.get("success"));
        assertTrue(String.valueOf(row.get("error")).contains(peer.id().value()), row.toString());
        assertFalse(captureOf(server, source.id().value()).contains(marker));
        assertFalse(captureOf(server, peer.id().value()).contains(marker));
    }

    @Test
    void synchronizedKeysReachOnlyTheEffectiveOnCohort(Server server) throws Exception {
        var source = server.panes().getFirst();
        var effective = source.split(SplitSpec.builder().build());
        var disabled = source.split(SplitSpec.builder().build());
        source.window().setSynchronizePanes(true);
        disabled.options().set("synchronize-panes", "off");
        String marker = "effective-cohort-marker";

        Typing.Sent sent = sendKeys(server, source.id().value(), marker);

        assertEquals(sorted(source.id().value(), effective.id().value()), sent.resolvedPaneIds());
        assertTrue(await(() -> captureOf(server, source.id().value()).contains(marker)));
        assertTrue(await(() -> captureOf(server, effective.id().value()).contains(marker)));
        assertFalse(captureOf(server, disabled.id().value()).contains(marker));
    }

    @Test
    void sourceOffIgnoresATruePeer(Server server) throws Exception {
        var source = server.panes().getFirst();
        var peer = source.split(SplitSpec.builder().build());
        source.window().setSynchronizePanes(true);
        source.options().set("synchronize-panes", "off");
        String marker = "source-off-marker";

        Typing.Sent sent = sendKeys(server, source.id().value(), marker);

        assertEquals(List.of(source.id().value()), sent.resolvedPaneIds());
        assertTrue(await(() -> captureOf(server, source.id().value()).contains(marker)));
        assertFalse(captureOf(server, peer.id().value()).contains(marker));
    }

    @Test
    void modalRecipientRefusesBeforeDelivery(Server server) {
        var source = server.panes().getFirst();
        var modal = source.split(SplitSpec.builder().build());
        source.window().setSynchronizePanes(true);
        modal.copyMode();
        assertTrue(modal.mode().isPresent(), "the refusal fixture did not enter a mode");
        String marker = "modal-refusal-marker";

        assertKeyRefused(server, source.id().value(), modal.id().value(), marker);
    }

    @Test
    void modalPaneOutsideTheEffectiveCohortDoesNotBlockKeys(Server server) throws Exception {
        var source = server.panes().getFirst();
        var recipient = source.split(SplitSpec.builder().build());
        var modal = source.split(SplitSpec.builder().build());
        source.window().setSynchronizePanes(true);
        modal.options().set("synchronize-panes", "off");
        modal.copyMode();
        String marker = "outside-modal-marker";

        Typing.Sent sent = sendKeys(server, source.id().value(), marker);

        assertEquals(sorted(source.id().value(), recipient.id().value()), sent.resolvedPaneIds());
        assertTrue(await(() -> captureOf(server, source.id().value()).contains(marker)));
        assertTrue(await(() -> captureOf(server, recipient.id().value()).contains(marker)));
        assertFalse(captureOf(server, modal.id().value()).contains(marker));
    }

    @Test
    void deadEffectiveRecipientRefusesBeforeDelivery(Server server) throws Exception {
        var source = server.panes().getFirst();
        var dead = source.split(SplitSpec.builder().build());
        dead.options().set("remain-on-exit", "on");
        dead.sendLine("exit");
        assertTrue(await(() -> "1".equals(dead.expand("#{pane_dead}"))), "the pane did not become dead");
        source.window().setSynchronizePanes(true);
        String marker = "dead-refusal-marker";

        assertKeyRefused(server, source.id().value(), dead.id().value(), marker);
    }

    @Test
    void batchResolvesAndGuardsEveryOperationFresh(Server server) throws Exception {
        var first = server.panes().getFirst();
        var firstPeer = first.split(SplitSpec.builder().build());
        first.window().setSynchronizePanes(true);
        var second =
                server.sessions().getFirst().newWindow("batch-modal").panes().getFirst();
        var modal = second.split(SplitSpec.builder().build());
        second.window().setSynchronizePanes(true);
        modal.copyMode();

        Map<String, Object> batch = map(Operations.sendKeysBatch(TestCalls.on(
                server,
                "operations",
                List.of(
                        send(first.id().value(), "batch-first-marker"),
                        send(second.id().value(), "batch-modal-marker"),
                        send("%999999", "batch-missing-marker")),
                "onError",
                "continue")));
        List<Map<String, Object>> rows = rows(batch);

        assertEquals(3, batch.get("completed"));
        assertEquals(true, rows.get(0).get("success"));
        assertEquals(
                sorted(first.id().value(), firstPeer.id().value()), rows.get(0).get("resolved_pane_ids"));
        assertEquals(false, rows.get(1).get("success"));
        assertEquals(
                sorted(second.id().value(), modal.id().value()), rows.get(1).get("resolved_pane_ids"));
        assertEquals(false, rows.get(2).get("success"));
        assertEquals(List.of(), rows.get(2).get("resolved_pane_ids"));
        assertTrue(await(() -> captureOf(server, first.id().value()).contains("batch-first-marker")));
        assertTrue(await(() -> captureOf(server, firstPeer.id().value()).contains("batch-first-marker")));
        assertFalse(captureOf(server, second.id().value()).contains("batch-modal-marker"));
        assertFalse(captureOf(server, modal.id().value()).contains("batch-modal-marker"));

        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport failing = new TmuxTransport() {
                @Override
                public CommandResult execute(CommandRequest request) {
                    boolean sending = request.commands().stream()
                            .anyMatch(command -> command.getFirst().equals("send-keys")
                                    || command.stream().anyMatch(argument -> argument.contains("'send-keys'")));
                    if (sending) {
                        throw new IllegalStateException("dispatch refused by fixture");
                    }
                    return processes.execute(request);
                }

                @Override
                public void close() {}
            };
            try (Server measured = Server.using(server.config(), failing)) {
                List<Map<String, Object>> failed = rows(map(Operations.sendKeysBatch(TestCalls.on(
                        measured,
                        "operations",
                        List.of(send(first.id().value(), "dispatch-failure-marker")),
                        "onError",
                        "continue"))));
                assertEquals(false, failed.getFirst().get("success"));
                assertTrue(String.valueOf(failed.getFirst().get("error")).contains("dispatch refused by fixture"));
                assertEquals(
                        sorted(first.id().value(), firstPeer.id().value()),
                        failed.getFirst().get("resolved_pane_ids"));
            }
        }
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
    void pasteChecksOnlyItsTargetAndDoesNotFanOut(Server server) throws Exception {
        assumeTrue(server.version().atLeast(SAFE_PASTE_CLEANUP));
        var source = server.panes().getFirst();
        var modal = source.split(SplitSpec.builder().build());
        source.window().setSynchronizePanes(true);
        modal.copyMode();
        String marker = "target-only-paste-marker";

        Typing.Pasted pasted =
                Typing.pasteText(TestCalls.on(server, "pane_id", source.id().value(), "text", marker));

        assertEquals(source.id().value(), pasted.paneId());
        assertTrue(await(() -> captureOf(server, source.id().value()).contains(marker)));
        assertFalse(captureOf(server, modal.id().value()).contains(marker));
        assertNoOwnedBuffers(server);
    }

    @Test
    void pasteRefusesAModalTargetBeforeCreatingABuffer(Server server) {
        var pane = server.panes().getFirst();
        pane.copyMode();
        String marker = "modal-paste-marker";

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> Typing.pasteText(TestCalls.on(server, "pane_id", pane.id().value(), "text", marker)));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("paste_text"), message);
        assertTrue(message.contains(pane.id().value()), message);
        assertFalse(captureOf(server, pane.id().value()).contains(marker));
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

    private static List<String> sorted(String... paneIds) {
        return java.util.stream.Stream.of(paneIds).sorted().toList();
    }

    private static Typing.Sent sendKeys(Server server, String paneId, String marker) {
        return Typing.sendKeys(TestCalls.on(server, "pane_id", paneId, "keys", List.of(marker), "literal", true));
    }

    private static void assertKeyRefused(Server server, String source, String blocked, String marker) {
        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> sendKeys(server, source, marker));
        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("send_keys"), message);
        assertTrue(message.contains(blocked), message);
        assertFalse(captureOf(server, source).contains(marker));
        assertFalse(captureOf(server, blocked).contains(marker));
    }

    private static Map<String, Object> send(String paneId, String marker) {
        return Map.of("pane_id", paneId, "keys", List.of(marker), "literal", true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> batch) {
        return (List<Map<String, Object>>) java.util.Objects.requireNonNull(batch.get("results"));
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

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
