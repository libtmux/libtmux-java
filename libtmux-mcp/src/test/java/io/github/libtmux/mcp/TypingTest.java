package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.SplitSpec;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void sendReservationCoversItsFinalPreflight(Server server) throws Exception {
        String pane = server.panes().getFirst().id().value();
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger snapshots = new AtomicInteger();
        List<CommandRequest> requests = new ArrayList<>();
        var owner = new java.util.concurrent.atomic.AtomicReference<Thread>();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport pausing = borrowing(request -> {
                CommandResult result = processes.execute(request);
                if (Thread.currentThread().equals(owner.get())) {
                    requests.add(request);
                }
                if (isPaneInputSnapshot(request) && snapshots.incrementAndGet() == 2) {
                    checked.countDown();
                    await(release);
                }
                return result;
            });
            try (Server measured = Server.using(server.config(), pausing);
                    var calls = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = calls.submit(() -> {
                    owner.set(Thread.currentThread());
                    return Typing.sendKeys(
                            TestCalls.on(measured, "pane_id", pane, "keys", List.of("first-lease"), "literal", true));
                });
                await(checked);
                try {
                    assertThrows(
                            IllegalStateException.class,
                            () -> Typing.sendKeys(TestCalls.on(
                                    measured, "pane_id", pane, "keys", List.of("second-lease"), "literal", true)));
                } finally {
                    release.countDown();
                }
                first.get(10, TimeUnit.SECONDS);
            }
        }

        List<Integer> preflights = indexes(requests, TypingTest::isPaneInputSnapshot);
        List<Integer> sends = indexes(requests, request -> hasCommand(request, "send-keys"));
        assertEquals(2, preflights.size());
        assertEquals(1, sends.size());
        assertEquals(preflights.getLast() + 1, sends.getFirst());
    }

    @Test
    void pasteReservationCoversStagingAndEmptyPaste(Server server) throws Exception {
        assumeTrue(server.version().atLeast(SAFE_PASTE_CLEANUP));
        String pane = server.panes().getFirst().id().value();
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger snapshots = new AtomicInteger();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport pausing = borrowing(request -> {
                CommandResult result = processes.execute(request);
                if (isPaneInputSnapshot(request) && snapshots.incrementAndGet() == 2) {
                    checked.countDown();
                    await(release);
                }
                return result;
            });
            try (Server measured = Server.using(server.config(), pausing);
                    var calls = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = calls.submit(
                        () -> Typing.pasteText(TestCalls.on(measured, "pane_id", pane, "text", "held-paste")));
                await(checked);
                try {
                    assertThrows(
                            IllegalStateException.class,
                            () -> Typing.pasteText(TestCalls.on(measured, "pane_id", pane, "text", "")));
                } finally {
                    release.countDown();
                }
                first.get(10, TimeUnit.SECONDS);
            }
        }

        assertNoOwnedBuffers(server);
    }

    @ParameterizedTest(name = "{0} socket alias")
    @ValueSource(strings = {"symbolic", "hard"})
    void reservationsContendAcrossPhysicalSocketAliases(String kind, Server server, @TempDir Path temporary)
            throws Exception {
        String pane = server.panes().getFirst().id().value();
        String socket = server.expand("#{socket_path}");
        Path alias = temporary.resolve("tmux-socket-alias");
        if (kind.equals("symbolic")) {
            Files.createSymbolicLink(alias, Path.of(socket));
        } else {
            Files.createLink(alias, Path.of(socket));
        }
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ProcessTransport firstProcesses = new ProcessTransport();
                ProcessTransport aliasProcesses = new ProcessTransport()) {
            TmuxTransport blocked = borrowing(request -> {
                if (hasCommand(request, "send-keys")) {
                    sending.countDown();
                    await(release);
                }
                return firstProcesses.execute(request);
            });
            TmuxTransport aliased = borrowing(request -> {
                CommandResult result = aliasProcesses.execute(request);
                return isPaneInputSnapshot(request)
                        ? new CommandResult(
                                result.exitCode(),
                                result.stdout().stream()
                                        .map(line -> line.replace(socket, alias.toString()))
                                        .toList(),
                                result.stderr())
                        : result;
            });
            var aliasConfig = server.config().toBuilder()
                    .endpoint(ServerEndpoint.socketPath(alias))
                    .build();
            try (Server first = Server.using(server.config(), blocked);
                    Server second = Server.using(aliasConfig, aliased);
                    var calls = Executors.newVirtualThreadPerTaskExecutor()) {
                var held = calls.submit(() -> Typing.sendKeys(
                        TestCalls.on(first, "pane_id", pane, "keys", List.of("alias-held"), "literal", true)));
                await(sending);
                try {
                    assertThrows(
                            IllegalStateException.class,
                            () -> Typing.sendKeys(TestCalls.on(
                                    second, "pane_id", pane, "keys", List.of("alias-refused"), "literal", true)));
                } finally {
                    release.countDown();
                }
                held.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void reservationCoversEveryInitiallyConfiguredMember(Server server) throws Exception {
        var source = server.panes().getFirst();
        var peer = source.split();
        source.window().setSynchronizePanes(true);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport blocked = borrowing(request -> {
                if (hasCommand(request, "send-keys")) {
                    sending.countDown();
                    await(release);
                }
                return processes.execute(request);
            });
            try (Server measured = Server.using(server.config(), blocked);
                    var calls = Executors.newVirtualThreadPerTaskExecutor()) {
                var held = calls.submit(() -> Typing.sendKeys(TestCalls.on(
                        measured, "pane_id", source.id().value(), "keys", List.of("cohort-held"), "literal", true)));
                await(sending);
                peer.options().set("synchronize-panes", "off");
                try {
                    assertThrows(
                            IllegalStateException.class,
                            () -> Typing.sendKeys(TestCalls.on(
                                    server,
                                    "pane_id",
                                    peer.id().value(),
                                    "keys",
                                    List.of("peer-refused"),
                                    "literal",
                                    true)));
                } finally {
                    release.countDown();
                }
                held.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void changedPanePlacementRefusesTheFinalSend(Server server) {
        String pane = server.panes().getFirst().id().value();
        String window = server.panes().getFirst().window().id().value();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicBoolean sent = new AtomicBoolean();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport changing = borrowing(request -> {
                CommandResult result = processes.execute(request);
                if (isPaneInputSnapshot(request) && snapshots.incrementAndGet() == 2) {
                    return new CommandResult(
                            result.exitCode(),
                            result.stdout().stream()
                                    .map(line -> line.replace(window, "@4294967295"))
                                    .toList(),
                            result.stderr());
                }
                if (hasCommand(request, "send-keys")) {
                    sent.set(true);
                }
                return result;
            });
            try (Server measured = Server.using(server.config(), changing)) {
                IllegalStateException refused = assertThrows(
                        IllegalStateException.class,
                        () -> Typing.sendKeys(TestCalls.on(
                                measured, "pane_id", pane, "keys", List.of("stale-placement"), "literal", true)));
                assertTrue(String.valueOf(refused.getMessage()).contains("changed"), refused.getMessage());
            }
        }

        assertEquals(2, snapshots.get());
        assertFalse(sent.get());
        Typing.sendKeys(
                TestCalls.on(server, "pane_id", pane, "keys", List.of("released-after-stale"), "literal", true));
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
    void inconsistentCallerSessionRefusesPaneInput(Server server) {
        var callerPane = server.panes().getFirst();
        var otherSession = server.newSession("caller-session-mismatch");
        var target = otherSession.windows().getFirst().panes().getFirst();
        String socket = server.expand("#{socket_path}");
        String sessionNumber = otherSession.id().value().substring(1);
        Map<String, String> environment = Map.of(
                "TMUX",
                socket + "," + server.expand("#{pid}") + "," + sessionNumber,
                "TMUX_PANE",
                callerPane.id().value());

        assertThrows(
                IllegalStateException.class,
                () -> Typing.sendKeys(TestCalls.withEnvironment(
                        server,
                        environment,
                        "pane_id",
                        target.id().value(),
                        "keys",
                        List.of("caller-session-mismatch-marker"),
                        "literal",
                        true)));
    }

    @Test
    void callerInAnotherSessionDoesNotBlockTheTarget(Server server) throws Exception {
        var callerPane = server.panes().getFirst();
        var otherSession = server.newSession("caller-other-session");
        var target = otherSession.windows().getFirst().panes().getFirst();
        String socket = server.expand("#{socket_path}");
        String callerSession = callerPane.window().session().id().value().substring(1);
        Map<String, String> environment = Map.of(
                "TMUX",
                socket + "," + server.expand("#{pid}") + "," + callerSession,
                "TMUX_PANE",
                callerPane.id().value());
        String marker = "off-window-caller-marker";

        Typing.sendKeys(TestCalls.withEnvironment(
                server, environment, "pane_id", target.id().value(), "keys", List.of(marker), "literal", true));

        assertTrue(await(() -> captureOf(server, target.id().value()).contains(marker)));
    }

    @Test
    void freshPaneSnapshotRevalidatesTheCallerPid(Server server) {
        var callerPane = server.panes().getFirst();
        var target = callerPane.split(SplitSpec.builder().build());
        String pid = server.expand("#{pid}");
        String wrongPid = Long.toString(Long.parseLong(pid) + 1);
        AtomicBoolean changed = new AtomicBoolean();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport changing = borrowing(request -> {
                CommandResult result = processes.execute(request);
                if (isPaneInputSnapshot(request) && changed.compareAndSet(false, true)) {
                    return new CommandResult(
                            result.exitCode(),
                            result.stdout().stream()
                                    .map(line -> line.replace(pid, wrongPid))
                                    .toList(),
                            result.stderr());
                }
                return result;
            });
            try (Server measured = Server.using(server.config(), changing)) {
                assertThrows(
                        IllegalStateException.class,
                        () -> Typing.sendKeys(TestCalls.asCaller(
                                measured,
                                callerPane.id().value(),
                                "pane_id",
                                target.id().value(),
                                "keys",
                                List.of("fresh-caller-pid-marker"),
                                "literal",
                                true)));
            }
        }

        assertTrue(changed.get(), "the snapshot seam did not change the reported generation");
        assertFalse(captureOf(server, target.id().value()).contains("fresh-caller-pid-marker"));
    }

    @Test
    void inputDisabledConfiguredPaneRefusesKeys(Server server) {
        var source = server.panes().getFirst();
        var disabled = source.split(SplitSpec.builder().build());
        source.window().setSynchronizePanes(true);
        server.run(List.of("select-pane", "-t", disabled.id().value(), "-d"));
        assertEquals("1", disabled.expand("#{pane_input_off}"));

        try {
            assertKeyRefused(server, source.id().value(), disabled.id().value(), "input-disabled-marker");
        } finally {
            server.run(List.of("select-pane", "-t", disabled.id().value(), "-e"));
        }
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
        List<CommandRequest> requests = new ArrayList<>();

        Typing.Pasted pasted;
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport recording = borrowing(request -> {
                requests.add(request);
                return processes.execute(request);
            });
            try (Server measured = Server.using(server.config(), recording)) {
                pasted = Typing.pasteText(TestCalls.on(measured, "pane_id", pane, "text", "Enter [C-c] done"));
            }
        }

        assertEquals(16, pasted.characters());
        assertTrue(String.valueOf(pasted.note()).contains("pass 'enter'"), String.valueOf(pasted.note()));
        assertEquals(
                1,
                requests.stream()
                        .filter(request -> hasCommand(request, "paste-buffer"))
                        .count());
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
    void emptyPasteGuardsBeforeItsBufferFreeNoOp(Server server) {
        var pane = server.panes().getFirst();
        List<CommandRequest> requests = new ArrayList<>();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport recording = borrowing(request -> {
                requests.add(request);
                return processes.execute(request);
            });
            try (Server measured = Server.using(server.config(), recording)) {
                Typing.Pasted empty = Typing.pasteText(
                        TestCalls.on(measured, "pane_id", pane.id().value(), "text", ""));
                assertEquals(0, empty.characters());
                assertTrue(String.valueOf(empty.note()).contains("nothing was sent"));
                pane.copyMode();
                try {
                    assertThrows(
                            IllegalStateException.class,
                            () -> Typing.pasteText(
                                    TestCalls.on(measured, "pane_id", pane.id().value(), "text", "")));
                } finally {
                    server.cmd("send-keys", "-t", pane.id().value(), "-X", "cancel");
                }
            }
        }

        assertEquals(
                2, requests.stream().filter(TypingTest::isPaneInputSnapshot).count());
        assertFalse(requests.stream().anyMatch(request -> hasCommand(request, "load-buffer")));
        assertFalse(requests.stream().anyMatch(request -> hasCommand(request, "paste-buffer")));
        assertNoOwnedBuffers(server);
    }

    @Test
    void pasteRechecksTargetAfterStaging(Server server) {
        assumeTrue(server.version().atLeast(SAFE_PASTE_CLEANUP));
        var pane = server.panes().getFirst();
        AtomicBoolean staged = new AtomicBoolean();
        AtomicBoolean pasted = new AtomicBoolean();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport changing = borrowing(request -> {
                CommandResult result = processes.execute(request);
                if (hasCommand(request, "load-buffer") && staged.compareAndSet(false, true)) {
                    pane.copyMode();
                }
                if (hasCommand(request, "paste-buffer")) {
                    pasted.set(true);
                }
                return result;
            });
            try (Server measured = Server.using(server.config(), changing)) {
                try {
                    assertThrows(
                            IllegalStateException.class,
                            () -> Typing.pasteText(
                                    TestCalls.on(measured, "pane_id", pane.id().value(), "text", "must-not-dispatch")));
                } finally {
                    server.cmd("send-keys", "-t", pane.id().value(), "-X", "cancel");
                }
            }
        }

        assertTrue(staged.get(), "the transition seam did not observe staging");
        assertFalse(pasted.get(), "paste dispatched after the target became modal");
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

    @Test
    void aDispatchFailureDuringPasteLeavesNothingOnTheServer(Server server) {
        assumeTrue(server.version().atLeast(SAFE_PASTE_CLEANUP));
        String pane = server.panes().get(0).id().value();
        try (ProcessTransport processes = new ProcessTransport()) {
            AtomicBoolean failed = new AtomicBoolean();
            TmuxTransport refusing = borrowing(request -> {
                if (hasCommand(request, "paste-buffer") && failed.compareAndSet(false, true)) {
                    throw new IllegalStateException("synthetic paste failure");
                }
                return processes.execute(request);
            });
            try (Server cut = Server.using(server.config(), refusing)) {
                assertThrows(
                        IllegalStateException.class,
                        () -> Typing.pasteText(TestCalls.on(cut, "pane_id", pane, "text", "secret-text")));
            }
            assertTrue(failed.get(), "the failure seam did not observe paste dispatch");
        }

        assertNoOwnedBuffers(server);
        assertFalse(captureOf(server, pane).contains("secret-text"));
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

    private static TmuxTransport borrowing(java.util.function.Function<CommandRequest, CommandResult> execute) {
        return new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return execute.apply(request);
            }

            @Override
            public void close() {}
        };
    }

    private static boolean hasCommand(CommandRequest request, String command) {
        return request.commands().stream()
                .anyMatch(argv ->
                        argv.getFirst().equals(command) || argv.stream().anyMatch(part -> part.contains(command)));
    }

    private static boolean isPaneInputSnapshot(CommandRequest request) {
        return request.commands().stream()
                .anyMatch(argv -> argv.getFirst().equals("list-panes")
                        && argv.stream().anyMatch(part -> part.contains("pane_in_mode")));
    }

    private static List<Integer> indexes(
            List<CommandRequest> requests, java.util.function.Predicate<CommandRequest> predicate) {
        return java.util.stream.IntStream.range(0, requests.size())
                .filter(index -> predicate.test(requests.get(index)))
                .boxed()
                .toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out arranging concurrent input");
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
