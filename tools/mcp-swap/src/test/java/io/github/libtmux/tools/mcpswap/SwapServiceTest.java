package io.github.libtmux.tools.mcpswap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SwapServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ServerSpec FIRST = new ServerSpec("/opt/first/libtmux-mcp", List.of("--socket", "/tmp/a"));
    private static final ServerSpec SECOND =
            new ServerSpec("/opt/second/libtmux-mcp", List.of("--socket-name", "demo"));

    @TempDir
    Path temporary;

    private Path home;
    private Map<String, String> environment;
    private List<Client> clients;

    @BeforeEach
    void setUp() throws IOException {
        home = temporary.resolve("home");
        Files.createDirectory(home);
        environment = Map.of(
                "XDG_CONFIG_HOME", home.resolve(".config").toString(),
                "XDG_STATE_HOME", home.resolve(".state").toString());
        clients = ClientRegistry.knownClients(home, environment);
    }

    @Test
    void usesSharedCrossPortLockAndJavaSpecificRecoveryArtifacts() {
        var client = clients.getFirst();

        assertEquals(home.resolve(".state/libtmux-mcp-dev/swap/state.lock"), SwapPaths.lock(home, environment));
        assertTrue(SwapPaths.backup(client).getFileName().toString().endsWith(".mcp-swap-java-user-backup"));
        assertTrue(SwapPaths.state(client).getFileName().toString().endsWith(".mcp-swap-java-user-backup.state"));
    }

    @Test
    void sharedLockExcludesPosixRecordLockClients() throws IOException, InterruptedException {
        try (var lock = SwapLock.acquire(home, environment)) {
            var guard = TransactionGuard.capture(clients, lock);
            guard.verifyLock();
            assertRecordLockHeld(lock.path());
        }
    }

    @Test
    void rejectingAClientAliasDoesNotReleaseTheRecordLock() throws IOException, InterruptedException {
        try (var lock = SwapLock.acquire(home, environment)) {
            var config = clients.getFirst().configPath();
            Files.createDirectories(Objects.requireNonNull(config.getParent()));
            Files.createLink(config, lock.path());
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var probe = Thread.ofPlatform().start(() -> {
                try {
                    TransactionGuard.capture(clients, lock);
                    failure.set(new AssertionError("lock alias was accepted"));
                } catch (IOException expected) {
                    failure.set(expected);
                }
            });
            probe.join();

            assertTrue(failure.get() instanceof IOException);
            assertTrue(String.valueOf(failure.get().getMessage()).contains("active swap lock"));
            assertRecordLockHeld(lock.path());
        }
    }

    private static void assertRecordLockHeld(Path path) throws IOException, InterruptedException {
        var script = """
                import fcntl, os, sys
                handle = os.fdopen(os.open(sys.argv[1], os.O_RDWR), "r+")
                try:
                    fcntl.lockf(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    sys.exit(0)
                sys.exit(1)
                """;
        var probe = new ProcessBuilder("python3", "-c", script, path.toString()).start();
        assertTrue(probe.waitFor(5, TimeUnit.SECONDS), "record-lock probe timed out");
        assertEquals(0, probe.exitValue(), "external fcntl client acquired the shared lock");
    }

    @Test
    void swapsAndRestoresAllEightClientsAsOneTransaction() throws IOException {
        var originals = seedAll();
        var service = new SwapService(home, environment);

        service.use(clients, clients, "tmux", FIRST, false);
        for (var client : clients) {
            assertEquals(
                    FIRST,
                    ConfigCodec.read(client, Files.readAllBytes(client.configPath()), "tmux")
                            .orElseThrow());
            assertTrue(Files.isRegularFile(SwapPaths.backup(client)));
            assertTrue(Files.isRegularFile(SwapPaths.state(client)));
        }

        service.use(clients.reversed(), clients, "tmux", SECOND, false);
        for (var client : clients) {
            assertEquals(
                    SECOND,
                    ConfigCodec.read(client, Files.readAllBytes(client.configPath()), "tmux")
                            .orElseThrow());
            assertArrayEquals(originals.get(client.name()), Files.readAllBytes(SwapPaths.backup(client)));
        }

        service.revert(clients.reversed(), clients, "tmux", false);
        for (var client : clients) {
            assertArrayEquals(originals.get(client.name()), Files.readAllBytes(client.configPath()));
            assertFalse(Files.exists(SwapPaths.backup(client)));
            assertFalse(Files.exists(SwapPaths.state(client)));
        }
    }

    @Test
    void rollsBackEveryClientWhenALaterCommitFails() throws IOException {
        var originals = seedAll();
        var failed = new boolean[] {false};
        var service = new SwapService(home, environment, (boundary, path) -> {
            if (!failed[0]
                    && boundary.equals("config-publish")
                    && path.equals(clients.get(4).configPath())) {
                failed[0] = true;
                throw new IOException("synthetic commit failure");
            }
        });

        assertThrows(IOException.class, () -> service.use(clients, clients, "tmux", FIRST, false));

        assertTrue(failed[0]);
        for (var client : clients) {
            assertArrayEquals(originals.get(client.name()), Files.readAllBytes(client.configPath()));
            assertFalse(Files.exists(SwapPaths.backup(client)));
            assertFalse(Files.exists(SwapPaths.state(client)));
        }
    }

    @Test
    void rejectsASelectedConfigAliasedToAnUnselectedClient() throws IOException {
        var originals = seedAll();
        var selected = clients.get(2);
        var unselected = clients.get(3);
        Files.delete(selected.configPath());
        Files.createSymbolicLink(selected.configPath(), unselected.configPath());
        var before = Files.readAllBytes(unselected.configPath());

        var service = new SwapService(home, environment);
        assertThrows(IOException.class, () -> service.use(List.of(selected), clients, "tmux", FIRST, false));

        assertTrue(Files.isSymbolicLink(selected.configPath()));
        assertArrayEquals(before, Files.readAllBytes(unselected.configPath()));
        for (var client : clients) {
            assertFalse(Files.exists(SwapPaths.backup(client)));
            assertFalse(Files.exists(SwapPaths.state(client)));
        }
        assertArrayEquals(originals.get(unselected.name()), before);
    }

    @Test
    void rejectsASelectedConfigHardLinkedToAnUnselectedClient() throws IOException {
        seedAll();
        var selected = clients.get(2);
        var unselected = clients.get(3);
        Files.delete(selected.configPath());
        Files.createLink(selected.configPath(), unselected.configPath());
        var before = Files.readAllBytes(unselected.configPath());

        var service = new SwapService(home, environment);
        assertThrows(IOException.class, () -> service.use(List.of(selected), clients, "tmux", FIRST, false));

        assertArrayEquals(before, Files.readAllBytes(selected.configPath()));
        assertArrayEquals(before, Files.readAllBytes(unselected.configPath()));
        assertFalse(Files.exists(SwapPaths.backup(selected)));
        assertFalse(Files.exists(SwapPaths.state(selected)));
    }

    @Test
    void rechecksUnselectedClientsBeforeEveryPublication() throws IOException {
        var originals = seedAll();
        var selected = clients.get(2);
        var unselected = clients.get(3);
        var raced = new boolean[] {false};
        var service = new SwapService(home, environment, (boundary, path) -> {
            if (!raced[0] && boundary.equals("state-publish")) {
                raced[0] = true;
                var human = unselected.configPath().resolveSibling("human.json");
                Files.writeString(human, "{\"human\":true}\n", StandardCharsets.UTF_8);
                Files.move(human, unselected.configPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        });

        assertThrows(IOException.class, () -> service.use(List.of(selected), clients, "tmux", FIRST, false));

        assertTrue(raced[0]);
        assertArrayEquals(originals.get(selected.name()), Files.readAllBytes(selected.configPath()));
        assertEquals("{\"human\":true}\n", Files.readString(unselected.configPath(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(SwapPaths.backup(selected)));
        assertFalse(Files.exists(SwapPaths.state(selected)));
    }

    @Test
    void rejectsAConfigDirectoryReplacementThatKeepsEveryFile() throws IOException {
        var originals = seedAll();
        var selected = clients.get(1);
        var parent = Objects.requireNonNull(selected.configPath().getParent());
        var displaced = parent.resolveSibling(".codex-displaced");
        var replaced = new boolean[] {false};
        var service = new SwapService(home, environment, (boundary, path) -> {
            if (!replaced[0] && boundary.equals("backup-publish") && path.equals(SwapPaths.backup(selected))) {
                replaced[0] = true;
                replaceDirectoryKeepingChildren(parent, displaced);
            }
        });

        assertThrows(IOException.class, () -> service.use(List.of(selected), clients, "tmux", FIRST, false));

        assertTrue(replaced[0]);
        assertArrayEquals(originals.get(selected.name()), Files.readAllBytes(selected.configPath()));
        assertFalse(Files.exists(SwapPaths.backup(selected)));
        assertFalse(Files.exists(SwapPaths.state(selected)));
    }

    @Test
    void refusesRevertAfterAConfigDirectoryReplacementThatKeepsEveryFile() throws IOException {
        seedAll();
        var selected = clients.get(1);
        var parent = Objects.requireNonNull(selected.configPath().getParent());
        var service = new SwapService(home, environment);
        service.use(List.of(selected), clients, "tmux", FIRST, false);

        replaceDirectoryKeepingChildren(parent, parent.resolveSibling(".codex-displaced"));

        assertThrows(IOException.class, () -> service.revert(List.of(selected), clients, "tmux", false));
        assertEquals(
                FIRST,
                ConfigCodec.read(selected, Files.readAllBytes(selected.configPath()), "tmux")
                        .orElseThrow());
        assertTrue(Files.isRegularFile(SwapPaths.backup(selected)));
        assertTrue(Files.isRegularFile(SwapPaths.state(selected)));
    }

    @Test
    void preservesALateFileAtAnAbsentConfigDestination() throws IOException {
        var selected = clients.get(2);
        Files.createDirectories(selected.configPath().getParent());
        var raced = new boolean[] {false};
        var service = new SwapService(home, environment, (boundary, path) -> {
            if (!raced[0] && boundary.equals("config-publish") && path.equals(selected.configPath())) {
                raced[0] = true;
                Files.writeString(path, "human\n", StandardCharsets.UTF_8);
            }
        });

        assertThrows(IOException.class, () -> service.use(List.of(selected), clients, "tmux", FIRST, false));

        assertTrue(raced[0]);
        assertEquals("human\n", Files.readString(selected.configPath(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(SwapPaths.backup(selected)));
        assertFalse(Files.exists(SwapPaths.state(selected)));
    }

    @Test
    void retainsAHumanReplacementAfterConfigPublication() throws IOException {
        seedAll();
        var selected = clients.getFirst();
        var human = "{\"human\":\"after publication\"}\n".getBytes(StandardCharsets.UTF_8);
        var replaced = new boolean[] {false};
        var service = new SwapService(home, environment, (boundary, path) -> {
            if (!replaced[0] && boundary.equals("config-post-publish")) {
                replaced[0] = true;
                replaceWith(path, human);
            }
        });

        assertThrows(IOException.class, () -> service.use(List.of(selected), clients, "tmux", FIRST, false));

        assertTrue(replaced[0]);
        assertArrayEquals(human, Files.readAllBytes(selected.configPath()));
        assertTrue(treeContains(human));
    }

    @Test
    void retainsAHumanReplacementBeforeTransactionRebaseline() throws IOException {
        seedAll();
        var selected = clients.getFirst();
        var human = "{\"human\":\"before rebaseline\"}\n".getBytes(StandardCharsets.UTF_8);
        var replaced = new boolean[] {false};
        var service = new SwapService(home, environment, (boundary, path) -> {
            if (!replaced[0] && boundary.equals("config-pre-update")) {
                replaced[0] = true;
                replaceWith(path, human);
            }
        });

        assertThrows(IOException.class, () -> service.use(List.of(selected), clients, "tmux", FIRST, false));

        assertTrue(replaced[0]);
        assertArrayEquals(human, Files.readAllBytes(selected.configPath()));
        assertTrue(treeContains(human));
    }

    @Test
    void retainsAHumanReplacementAfterStatePublication() throws IOException {
        seedAll();
        var selected = clients.getFirst();
        var human = "human state replacement\n".getBytes(StandardCharsets.UTF_8);
        var replaced = new boolean[] {false};
        var service = new SwapService(home, environment, (boundary, path) -> {
            if (!replaced[0] && boundary.equals("state-post-publish")) {
                replaced[0] = true;
                replaceWith(path, human);
            }
        });

        assertThrows(IOException.class, () -> service.use(List.of(selected), clients, "tmux", FIRST, false));

        assertTrue(replaced[0]);
        assertArrayEquals(human, Files.readAllBytes(SwapPaths.state(selected)));
        assertTrue(treeContains(human));
    }

    @Test
    void retainsAHumanReplacementOfACleanupArtifact() throws IOException {
        seedAll();
        var selected = clients.getFirst();
        var human = "human cleanup replacement\n".getBytes(StandardCharsets.UTF_8);
        var replaced = new boolean[] {false};
        var service = new SwapService(home, environment, (boundary, path) -> {
            if (!replaced[0] && boundary.equals("config-cleanup-remove")) {
                replaced[0] = true;
                replaceWith(path, human);
            }
        });

        assertThrows(IOException.class, () -> service.use(List.of(selected), clients, "tmux", FIRST, false));

        assertTrue(replaced[0]);
        assertEquals(
                FIRST,
                ConfigCodec.read(selected, Files.readAllBytes(selected.configPath()), "tmux")
                        .orElseThrow());
        assertTrue(treeContains(human));
        assertTrue(Files.isRegularFile(SwapPaths.backup(selected)));
        assertTrue(Files.isRegularFile(SwapPaths.state(selected)));
    }

    @Test
    void retainsAHumanReplacementDuringRollbackRemoval() throws IOException {
        seedAll();
        var first = clients.getFirst();
        var second = clients.get(1);
        var human = "{\"human\":\"rollback replacement\"}\n".getBytes(StandardCharsets.UTF_8);
        var replaced = new boolean[] {false};
        var service = new SwapService(home, environment, (boundary, path) -> {
            if (boundary.equals("config-publish") && path.equals(second.configPath())) {
                throw new IOException("synthetic later failure");
            }
            if (!replaced[0] && boundary.equals("config-rollback-remove") && path.equals(first.configPath())) {
                replaced[0] = true;
                replaceWith(path, human);
            }
        });

        assertThrows(IOException.class, () -> service.use(List.of(first, second), clients, "tmux", FIRST, false));

        assertTrue(replaced[0]);
        assertArrayEquals(human, Files.readAllBytes(first.configPath()));
        assertTrue(treeContains(human));
        assertTrue(Files.isRegularFile(SwapPaths.backup(first)));
        assertTrue(Files.isRegularFile(SwapPaths.state(first)));
    }

    @Test
    void refusesRevertAfterAHumanEditAndKeepsRecovery() throws IOException {
        seedAll();
        var selected = clients.getFirst();
        var service = new SwapService(home, environment);
        service.use(List.of(selected), clients, "tmux", FIRST, false);
        var backup = Files.readAllBytes(SwapPaths.backup(selected));
        Files.writeString(selected.configPath(), "{\"human\":true}\n", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> service.revert(List.of(selected), clients, "tmux", false));

        assertEquals("{\"human\":true}\n", Files.readString(selected.configPath(), StandardCharsets.UTF_8));
        assertArrayEquals(backup, Files.readAllBytes(SwapPaths.backup(selected)));
        assertTrue(Files.isRegularFile(SwapPaths.state(selected)));
    }

    @Test
    void refusesAByteIdenticalReplacementOfTheRecoveryBackup() throws IOException {
        seedAll();
        var selected = clients.getFirst();
        var service = new SwapService(home, environment);
        service.use(List.of(selected), clients, "tmux", FIRST, false);
        var backup = SwapPaths.backup(selected);
        var replacement = backup.resolveSibling("replacement");
        Files.copy(backup, replacement);
        Files.setPosixFilePermissions(replacement, Files.getPosixFilePermissions(backup));
        Files.move(replacement, backup, StandardCopyOption.REPLACE_EXISTING);

        assertThrows(IOException.class, () -> service.revert(List.of(selected), clients, "tmux", false));

        assertEquals(
                FIRST,
                ConfigCodec.read(selected, Files.readAllBytes(selected.configPath()), "tmux")
                        .orElseThrow());
        assertTrue(Files.isRegularFile(backup));
        assertTrue(Files.isRegularFile(SwapPaths.state(selected)));
    }

    @Test
    void refusesAByteIdenticalReplacementOfTheSwappedConfig() throws IOException {
        seedAll();
        var selected = clients.getFirst();
        var service = new SwapService(home, environment);
        service.use(List.of(selected), clients, "tmux", FIRST, false);
        var replacement = selected.configPath().resolveSibling("replacement.json");
        Files.copy(selected.configPath(), replacement);
        Files.setPosixFilePermissions(replacement, Files.getPosixFilePermissions(selected.configPath()));
        Files.move(replacement, selected.configPath(), StandardCopyOption.REPLACE_EXISTING);

        assertThrows(IOException.class, () -> service.revert(List.of(selected), clients, "tmux", false));

        assertEquals(
                FIRST,
                ConfigCodec.read(selected, Files.readAllBytes(selected.configPath()), "tmux")
                        .orElseThrow());
        assertTrue(Files.isRegularFile(SwapPaths.backup(selected)));
        assertTrue(Files.isRegularFile(SwapPaths.state(selected)));
    }

    @Test
    void rejectsARecoveryRecordWithAChecksummedWrongType() throws IOException {
        seedAll();
        var selected = clients.getFirst();
        var service = new SwapService(home, environment);
        service.use(List.of(selected), clients, "tmux", FIRST, false);
        var state = SwapPaths.state(selected);
        var root = (ObjectNode) JSON.readTree(state.toFile());
        root.remove("checksum");
        root.put("symbolicLink", "false");
        root.put("checksum", FileSnapshot.sha256(JSON.writeValueAsBytes(root)));
        Files.write(state, JSON.writeValueAsBytes(root));

        assertThrows(IOException.class, () -> service.revert(List.of(selected), clients, "tmux", false));

        assertEquals(
                FIRST,
                ConfigCodec.read(selected, Files.readAllBytes(selected.configPath()), "tmux")
                        .orElseThrow());
        assertTrue(Files.isRegularFile(SwapPaths.backup(selected)));
        assertTrue(Files.isRegularFile(state));
    }

    @Test
    void rejectsLegacyRecoverySchemasEvenWithAValidChecksum() throws IOException {
        seedAll();
        var selected = clients.getFirst();
        var service = new SwapService(home, environment);
        service.use(List.of(selected), clients, "tmux", FIRST, false);
        var state = SwapPaths.state(selected);
        var root = (ObjectNode) JSON.readTree(state.toFile());
        root.remove("checksum");
        root.put("version", 1);
        root.put("checksum", FileSnapshot.sha256(JSON.writeValueAsBytes(root)));
        Files.write(state, JSON.writeValueAsBytes(root));

        var failure = assertThrows(IOException.class, () -> service.revert(List.of(selected), clients, "tmux", false));

        assertTrue(String.valueOf(failure.getMessage()).contains("unsupported recovery state version"));
        assertEquals(
                FIRST,
                ConfigCodec.read(selected, Files.readAllBytes(selected.configPath()), "tmux")
                        .orElseThrow());
        assertTrue(Files.isRegularFile(SwapPaths.backup(selected)));
        assertTrue(Files.isRegularFile(state));
    }

    @Test
    void rejectsTrailingDataInARecoveryRecord() throws IOException {
        seedAll();
        var selected = clients.getFirst();
        var service = new SwapService(home, environment);
        service.use(List.of(selected), clients, "tmux", FIRST, false);
        var state = SwapPaths.state(selected);
        Files.writeString(state, Files.readString(state, StandardCharsets.UTF_8) + " {}\n", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> service.revert(List.of(selected), clients, "tmux", false));

        assertEquals(
                FIRST,
                ConfigCodec.read(selected, Files.readAllBytes(selected.configPath()), "tmux")
                        .orElseThrow());
        assertTrue(Files.isRegularFile(SwapPaths.backup(selected)));
        assertTrue(Files.isRegularFile(state));
    }

    @Test
    void rejectsDuplicateRecoveryFieldsBeforeChecksumValidation() throws IOException {
        seedAll();
        var selected = clients.getFirst();
        var service = new SwapService(home, environment);
        service.use(List.of(selected), clients, "tmux", FIRST, false);
        var state = SwapPaths.state(selected);
        var encoded = Files.readString(state, StandardCharsets.UTF_8);
        var duplicated = encoded.replaceFirst("\\\"client\\\":", "\\\"client\\\":\\\"attacker\\\",\\\"client\\\":");

        assertThrows(IOException.class, () -> RecoveryRecord.decode(duplicated.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void keepsAConfigSymlinkAcrossUseAndRevert() throws IOException {
        var selected = clients.getFirst();
        Files.createDirectories(selected.configPath().getParent());
        var target = home.resolve("actual-claude.json");
        var original = "{\"keep\":true}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);
        Files.createSymbolicLink(selected.configPath(), target);
        var service = new SwapService(home, environment);

        service.use(List.of(selected), clients, "tmux", FIRST, false);
        assertTrue(Files.isSymbolicLink(selected.configPath()));
        assertEquals(
                FIRST,
                ConfigCodec.read(selected, Files.readAllBytes(target), "tmux").orElseThrow());

        service.revert(List.of(selected), clients, "tmux", false);
        assertTrue(Files.isSymbolicLink(selected.configPath()));
        assertArrayEquals(original, Files.readAllBytes(target));
    }

    @Test
    void backsUpASymlinkedConfigAcrossFilesystems() throws IOException {
        var sharedMemory = Path.of("/dev/shm");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(sharedMemory));
        org.junit.jupiter.api.Assumptions.assumeFalse(
                Files.getFileStore(sharedMemory).equals(Files.getFileStore(home)));
        var foreignDirectory = Files.createTempDirectory(sharedMemory, "libtmux-java-mcp-swap-");
        var selected = clients.get(1);
        var original = "title = \"foreign\"\n".getBytes(StandardCharsets.UTF_8);
        var target = foreignDirectory.resolve("config.toml");
        try {
            Files.write(target, original);
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));
            Files.createDirectories(selected.configPath().getParent());
            Files.createSymbolicLink(selected.configPath(), target);
            var service = new SwapService(home, environment);

            service.use(List.of(selected), clients, "tmux", FIRST, false);
            assertEquals(
                    FIRST,
                    ConfigCodec.read(selected, Files.readAllBytes(target), "tmux")
                            .orElseThrow());

            service.revert(List.of(selected), clients, "tmux", false);
            assertArrayEquals(original, Files.readAllBytes(target));
            assertFalse(Files.exists(SwapPaths.backup(selected)));
            assertFalse(Files.exists(SwapPaths.state(selected)));
        } finally {
            Files.deleteIfExists(selected.configPath());
            Files.deleteIfExists(target);
            Files.deleteIfExists(foreignDirectory);
        }
    }

    @Test
    void refusesRevertAfterAConfigSymlinkIsRecreatedWithTheSameTarget() throws IOException {
        var originals = seedAll();
        var selected = clients.get(1);
        var target = home.resolve("actual-codex.toml");
        Files.write(target, originals.get(selected.name()));
        Files.delete(selected.configPath());
        Files.createSymbolicLink(selected.configPath(), target);
        var service = new SwapService(home, environment);
        service.use(List.of(selected), clients, "tmux", FIRST, false);

        var priorLink = selected.configPath().resolveSibling("prior-config-link");
        Files.move(selected.configPath(), priorLink);
        Files.createSymbolicLink(selected.configPath(), target);

        assertThrows(IOException.class, () -> service.revert(List.of(selected), clients, "tmux", false));
        assertTrue(Files.isSymbolicLink(selected.configPath()));
        assertEquals(
                FIRST,
                ConfigCodec.read(selected, Files.readAllBytes(target), "tmux").orElseThrow());
        assertTrue(Files.isRegularFile(SwapPaths.backup(selected)));
        assertTrue(Files.isRegularFile(SwapPaths.state(selected)));
        assertTrue(Files.isSymbolicLink(priorLink));
    }

    @Test
    void refusesRevertAfterASymlinkedConfigsLogicalParentIsReplaced() throws IOException {
        var originals = seedAll();
        var selected = clients.get(1);
        var target = home.resolve("actual-codex.toml");
        Files.write(target, originals.get(selected.name()));
        Files.delete(selected.configPath());
        Files.createSymbolicLink(selected.configPath(), target);
        var service = new SwapService(home, environment);
        service.use(List.of(selected), clients, "tmux", FIRST, false);

        var parent = Objects.requireNonNull(selected.configPath().getParent());
        replaceDirectoryKeepingChildren(parent, parent.resolveSibling(".codex-displaced"));

        assertThrows(IOException.class, () -> service.revert(List.of(selected), clients, "tmux", false));
        assertTrue(Files.isSymbolicLink(selected.configPath()));
        assertEquals(
                FIRST,
                ConfigCodec.read(selected, Files.readAllBytes(target), "tmux").orElseThrow());
        assertTrue(Files.isRegularFile(SwapPaths.backup(selected)));
        assertTrue(Files.isRegularFile(SwapPaths.state(selected)));
    }

    @Test
    void retainsRecoveryWhenAConfigSymlinkIsRetargetedDuringPublish() throws IOException {
        var selected = clients.getFirst();
        Files.createDirectories(selected.configPath().getParent());
        var originalTarget = home.resolve("original.json");
        var humanTarget = home.resolve("human.json");
        Files.writeString(originalTarget, "{\"original\":true}\n", StandardCharsets.UTF_8);
        Files.writeString(humanTarget, "{\"human\":true}\n", StandardCharsets.UTF_8);
        Files.createSymbolicLink(selected.configPath(), originalTarget);
        var retargeted = new boolean[] {false};
        var service = new SwapService(home, environment, (boundary, path) -> {
            if (!retargeted[0] && boundary.equals("config-publish")) {
                retargeted[0] = true;
                Files.delete(path);
                Files.createSymbolicLink(path, humanTarget);
            }
        });

        assertThrows(IOException.class, () -> service.use(List.of(selected), clients, "tmux", FIRST, false));

        assertTrue(retargeted[0]);
        assertEquals(humanTarget, selected.configPath().toRealPath());
        assertEquals("{\"human\":true}\n", Files.readString(humanTarget, StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(SwapPaths.backup(selected)));
        assertTrue(Files.isRegularFile(SwapPaths.state(selected)));
        try (var paths = Files.list(home)) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().contains(".mcp-swap-old-")));
        }
    }

    @Test
    void refusesAnUnsafeLockAndSerializesASecondOwner() throws IOException, InterruptedException {
        var lockPath = SwapPaths.lock(home, environment);
        var lockDirectory = Objects.requireNonNull(lockPath.getParent());
        var stateDirectory = Objects.requireNonNull(lockDirectory.getParent());
        Files.createDirectories(lockDirectory);
        Files.setPosixFilePermissions(stateDirectory, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(lockDirectory, PosixFilePermissions.fromString("rwx------"));
        var target = home.resolve("lock-target");
        Files.writeString(target, "not a lock");
        Files.createSymbolicLink(lockPath, target);
        assertThrows(IOException.class, () -> SwapLock.acquire(home, environment));

        Files.delete(lockPath);
        var first = SwapLock.acquire(home, environment);
        var attempted = new CountDownLatch(1);
        var acquired = new AtomicReference<SwapLock>();
        var failure = new AtomicReference<Throwable>();
        var contender = Thread.ofPlatform().start(() -> {
            attempted.countDown();
            try {
                acquired.set(SwapLock.acquire(home, environment));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            assertTrue(attempted.await(1, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertTrue(contender.isAlive());
            first.verify();
            assertRecordLockHeld(first.path());
        } finally {
            first.close();
        }
        contender.join(TimeUnit.SECONDS.toMillis(3));
        assertFalse(contender.isAlive());
        assertTrue(failure.get() == null, String.valueOf(failure.get()));
        try (var second = Objects.requireNonNull(acquired.get())) {
            second.verify();
        }
    }

    @Test
    void dryRunRejectsAnUnsafeLockWithoutChangingIt() throws IOException {
        seedAll();
        var lockPath = SwapPaths.lock(home, environment);
        var lockDirectory = Objects.requireNonNull(lockPath.getParent());
        var stateDirectory = Objects.requireNonNull(lockDirectory.getParent());
        Files.createDirectories(lockDirectory);
        Files.setPosixFilePermissions(stateDirectory, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(lockDirectory, PosixFilePermissions.fromString("rwx------"));
        var target = home.resolve("lock-target");
        Files.writeString(target, "not a lock");
        Files.createSymbolicLink(lockPath, target);
        var before = tree();

        assertThrows(
                IOException.class, () -> new SwapService(home, environment).use(clients, clients, "tmux", FIRST, true));

        assertEquals(before, tree());
        assertTrue(Files.isSymbolicLink(lockPath));
    }

    @Test
    void dryRunCreatesNoFilesOrDirectories() throws IOException {
        var service = new SwapService(home, environment);
        var before = tree();

        service.use(clients, clients, "tmux", FIRST, true);

        assertEquals(before, tree());
    }

    private Map<String, byte[]> seedAll() throws IOException {
        Map<String, byte[]> originals = new LinkedHashMap<>();
        for (var client : clients) {
            Files.createDirectories(client.configPath().getParent());
            var raw =
                    switch (client.format()) {
                        case JSON -> "{\n  \"unrelated\": true\n}\n";
                        case JSONC -> "{\n  // keep\n  \"unrelated\": true,\n}\n";
                        case TOML -> "title = \"keep\"\n";
                    };
            var bytes = raw.getBytes(StandardCharsets.UTF_8);
            Files.write(client.configPath(), bytes);
            Files.setPosixFilePermissions(client.configPath(), PosixFilePermissions.fromString("rw-r-----"));
            originals.put(client.name(), bytes);
        }
        return originals;
    }

    private static void replaceDirectoryKeepingChildren(Path directory, Path displaced) throws IOException {
        Files.move(directory, displaced);
        Files.createDirectory(directory);
        try (var children = Files.list(displaced)) {
            for (var child : children.toList()) {
                Files.move(child, directory.resolve(child.getFileName()));
            }
        }
    }

    private static void replaceWith(Path path, byte[] contents) throws IOException {
        var replacement = path.resolveSibling("." + path.getFileName() + ".human");
        Files.write(replacement, contents);
        Files.setPosixFilePermissions(replacement, PosixFilePermissions.fromString("rw-------"));
        Files.move(replacement, path, StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean treeContains(byte[] contents) throws IOException {
        try (var paths = Files.walk(home)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                if (java.util.Arrays.equals(contents, Files.readAllBytes(path))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> tree() throws IOException {
        try (var paths = Files.walk(home)) {
            return paths.map(home::relativize).map(Path::toString).sorted().toList();
        }
    }
}
