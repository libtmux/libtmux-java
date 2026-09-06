package io.github.libtmux.tools.mcpswap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SwapServiceTest {
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

    private List<String> tree() throws IOException {
        try (var paths = Files.walk(home)) {
            return paths.map(home::relativize).map(Path::toString).sorted().toList();
        }
    }
}
