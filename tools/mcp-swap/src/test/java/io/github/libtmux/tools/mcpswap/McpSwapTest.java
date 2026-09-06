package io.github.libtmux.tools.mcpswap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class McpSwapTest {
    @TempDir
    Path temporary;

    private Path home;
    private Path repository;
    private Map<String, String> environment;
    private List<Client> clients;
    private ByteArrayOutputStream stdout;
    private ByteArrayOutputStream stderr;
    private RecordingProcess process;

    @BeforeEach
    void setUp() throws IOException {
        home = temporary.resolve("home");
        repository = temporary.resolve("repo");
        Files.createDirectories(home);
        Files.createDirectories(repository.resolve("libtmux-mcp/build/install/libtmux-mcp/bin"));
        Files.writeString(repository.resolve("gradlew"), "#!/bin/sh\n", StandardCharsets.UTF_8);
        repository.resolve("gradlew").toFile().setExecutable(true);
        environment = Map.of("XDG_CONFIG_HOME", home.resolve("xdg").toString());
        clients = ClientRegistry.knownClients(home, environment);
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
        process = new RecordingProcess();
    }

    @Test
    void noArgumentsAndHelpDescribeTheNativeCommands() {
        assertEquals(0, run());
        assertTrue(output().contains("usage: mcp-swap"));

        stdout.reset();
        assertEquals(0, run("use", "--help"));
        assertTrue(output().contains("--source"));
        assertTrue(output().contains("--socket-name"));
    }

    @Test
    void dryRunPreflightsEveryClientWithoutBuildingOrWriting() throws IOException {
        var originals = seedAll();

        assertEquals(
                0,
                run(
                        "use",
                        "--dry-run",
                        "--source",
                        "gradle",
                        "--socket",
                        "/tmp/demo/s",
                        "--cli",
                        "pi,antigravity,cursor,claude,codex,gemini,grok,opencode"));

        assertTrue(process.commands.isEmpty());
        for (var client : clients) {
            assertArrayEquals(originals.get(client.name()), Files.readAllBytes(client.configPath()));
            assertTrue(output().lines()
                    .anyMatch(line -> line.startsWith(client.name()) && line.contains("would set tmux")));
            assertFalse(Files.exists(SwapPaths.backup(client)));
            assertFalse(Files.exists(SwapPaths.state(client)));
        }
    }

    @Test
    void distributionUseBuildsOnceAndRevertRestoresEveryByte() throws IOException {
        var originals = seedAll();
        var launcher = repository.resolve("libtmux-mcp/build/install/libtmux-mcp/bin/libtmux-mcp");
        process.afterRun = () -> {
            try {
                Files.writeString(launcher, "#!/bin/sh\n", StandardCharsets.UTF_8);
                launcher.toFile().setExecutable(true);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        };

        assertEquals(0, run("use", "--socket-name", "demo"));
        assertEquals(1, process.commands.size());
        assertTrue(process.commands.getFirst().contains(":libtmux-mcp:installDist"));
        for (var client : clients) {
            assertEquals(
                    new ServerSpec(launcher.toString(), List.of("--socket-name", "demo")),
                    ConfigCodec.read(client, Files.readAllBytes(client.configPath()), "tmux")
                            .orElseThrow());
        }

        assertEquals(0, run("revert"));
        for (var client : clients) {
            assertArrayEquals(originals.get(client.name()), Files.readAllBytes(client.configPath()));
            assertFalse(Files.exists(SwapPaths.backup(client)));
            assertFalse(Files.exists(SwapPaths.state(client)));
        }
    }

    @Test
    void statusAndDetectRemainReadOnlyWhenOneConfigIsMalformed() throws IOException {
        seedAll();
        Files.writeString(clients.getFirst().configPath(), "[]\n", StandardCharsets.UTF_8);
        var before = tree();

        assertEquals(1, run("status"));
        assertTrue(error().contains("claude"));
        assertTrue(output().contains("codex"));

        stdout.reset();
        stderr.reset();
        assertEquals(0, run("detect"));
        assertTrue(output().contains("pi"));
        assertTrue(output().contains("needs the pi-mcp-adapter"));
        assertTreeEquals(before);
    }

    @Test
    void rejectsRetiredAndAmbiguousLauncherArguments() {
        assertEquals(2, run("use", "--safety", "destructive"));
        assertTrue(error().contains("LIBTMUX_SAFETY"));

        stderr.reset();
        assertEquals(2, run("use", "--source", "path"));
        assertTrue(error().contains("--bin"));

        stderr.reset();
        assertEquals(2, run("use", "--socket", "/tmp/s", "--socket-name", "demo"));
        assertTrue(error().contains("mutually exclusive"));
    }

    private int run(String... arguments) {
        return McpSwap.run(
                arguments,
                new McpSwap.Context(
                        home,
                        environment,
                        repository,
                        new PrintStream(stdout, true, StandardCharsets.UTF_8),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8),
                        process));
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
            originals.put(client.name(), bytes);
        }
        return originals;
    }

    private Map<String, byte[]> tree() throws IOException {
        Map<String, byte[]> found = new LinkedHashMap<>();
        try (var paths = Files.walk(temporary)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                found.put(temporary.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        return found;
    }

    private void assertTreeEquals(Map<String, byte[]> expected) throws IOException {
        var actual = tree();
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((path, bytes) -> assertArrayEquals(bytes, actual.get(path), path));
    }

    private String output() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String error() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    private static final class RecordingProcess implements McpSwap.ProcessRunner {
        private final java.util.ArrayList<List<String>> commands = new java.util.ArrayList<>();
        private Runnable afterRun = () -> {};

        @Override
        public int run(List<String> command, Path directory) {
            commands.add(List.copyOf(command));
            afterRun.run();
            return 0;
        }
    }
}
