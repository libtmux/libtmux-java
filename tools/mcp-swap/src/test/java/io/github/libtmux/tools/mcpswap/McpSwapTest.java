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
import org.jspecify.annotations.Nullable;
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
    private RecordingPreflight preflight;

    @BeforeEach
    void setUp() throws IOException {
        home = temporary.resolve("home");
        repository = temporary.resolve("repo");
        Files.createDirectories(home);
        Files.createDirectories(repository.resolve("libtmux-mcp/build/install/libtmux-mcp/bin"));
        Files.writeString(repository.resolve("gradlew"), "#!/bin/sh\n", StandardCharsets.UTF_8);
        repository.resolve("gradlew").toFile().setExecutable(true);
        var binaries = home.resolve("bin");
        Files.createDirectories(binaries);
        for (var binary : List.of("claude", "codex", "cursor-agent", "gemini", "grok", "agy", "opencode", "pi")) {
            var executable = binaries.resolve(binary);
            Files.writeString(executable, "#!/bin/sh\n", StandardCharsets.UTF_8);
            executable.toFile().setExecutable(true);
        }
        environment = new LinkedHashMap<>();
        environment.put("XDG_CONFIG_HOME", home.resolve("xdg").toString());
        environment.put("PATH", binaries.toString());
        clients = ClientRegistry.knownClients(home, environment);
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
        process = new RecordingProcess();
        preflight = new RecordingPreflight();
    }

    @Test
    void noArgumentsAndHelpDescribeTheNativeCommands() {
        assertEquals(0, run());
        assertTrue(output().contains("usage: mcp-swap"));

        for (var command : List.of("detect", "status", "use", "revert", "doctor")) {
            stdout.reset();
            assertEquals(0, run(command, "--help"));
            assertTrue(output().contains("usage: mcp-swap " + command));
        }

        stdout.reset();
        assertEquals(0, run("use", "--help"));
        assertTrue(output().contains("--source"));
        assertTrue(output().contains("--socket-name"));
        assertTrue(output().contains("--scope"));
        assertTrue(output().contains("--no-preflight"));
    }

    @Test
    void rejectsOptionsThatACommandWouldIgnore() {
        assertEquals(2, run("detect", "--cli", "claude"));
        assertTrue(error().contains("--cli does not apply to detect"));

        stderr.reset();
        assertEquals(2, run("status", "--dry-run"));
        assertTrue(error().contains("--dry-run does not apply to status"));

        stderr.reset();
        assertEquals(2, run("revert", "--source", "gradle"));
        assertTrue(error().contains("--source does not apply to revert"));

        stderr.reset();
        assertEquals(2, run("doctor", "--dry-run"));
        assertTrue(error().contains("--dry-run does not apply to doctor"));
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
        assertTrue(preflight.specs.isEmpty());
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
        assertEquals(8, preflight.specs.size());
        assertTrue(process.commands.getFirst().contains(":libtmux-mcp:installDist"));
        for (var client : clients) {
            var target = client.scoped(client.name().equals("claude") ? Scope.PROJECT : Scope.USER, repository);
            assertEquals(
                    new ServerSpec(launcher.toString(), List.of("--socket-name", "demo")),
                    ConfigCodec.read(target, Files.readAllBytes(client.configPath()), "tmux")
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
    void detectRequiresBothTheBinaryAndConfigUnlessAClientIsExplicit() throws IOException {
        seedAll();
        Files.delete(home.resolve("bin/claude"));

        assertEquals(0, run("detect"));
        assertTrue(output().lines().anyMatch(line -> line.contains("claude") && line.contains("binary missing")));

        stdout.reset();
        assertEquals(0, run("use", "--dry-run", "--source", "gradle"));
        assertFalse(output().lines().anyMatch(line -> line.startsWith("claude")));

        stdout.reset();
        assertEquals(0, run("use", "--dry-run", "--source", "gradle", "--cli", "claude"));
        assertTrue(output().lines().anyMatch(line -> line.startsWith("claude:project")));
    }

    @Test
    void claudeScopesCoexistAndFullRevertUnwindsThemInLifoOrder() throws IOException {
        var claude = clients.getFirst();
        Files.createDirectories(claude.configPath().getParent());
        var original = """
                {
                  "mcpServers": {
                    "tmux": {"type":"stdio","command":"published","args":[],"env":{}}
                  },
                  "projects": {}
                }
                """.getBytes(StandardCharsets.UTF_8);
        Files.write(claude.configPath(), original);

        assertEquals(0, run("use", "--source", "gradle", "--cli", "claude", "--socket-name", "project"));
        assertEquals(
                0, run("use", "--source", "gradle", "--cli", "claude", "--scope", "user", "--socket-name", "user"));

        stdout.reset();
        assertEquals(0, run("status", "--cli", "claude"));
        assertTrue(output().contains("claude:user"));
        assertTrue(output().contains("claude:project"));

        var swapped = Files.readAllBytes(claude.configPath());
        assertEquals(1, run("revert", "--cli", "claude", "--scope", "project"));
        assertArrayEquals(swapped, Files.readAllBytes(claude.configPath()));
        assertTrue(error().contains("newer recovery layer"));

        stderr.reset();
        assertEquals(0, run("revert", "--cli", "claude"));
        assertArrayEquals(original, Files.readAllBytes(claude.configPath()));
        try (var paths = Files.walk(home)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains("mcp-swap-java")));
        }
    }

    @Test
    void claudeScopesCanBeRevertedIndependentlyFromTheNewestLayer() throws IOException {
        var claude = clients.getFirst();
        Files.createDirectories(claude.configPath().getParent());
        var original = "{\"mcpServers\":{},\"projects\":{}}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(claude.configPath(), original);

        assertEquals(0, run("use", "--source", "gradle", "--cli", "claude", "--socket-name", "project"));
        assertEquals(
                0, run("use", "--source", "gradle", "--cli", "claude", "--scope", "user", "--socket-name", "user"));

        assertEquals(0, run("revert", "--cli", "claude", "--scope", "user"));
        var project = claude.scoped(Scope.PROJECT, repository);
        var user = claude.scoped(Scope.USER, repository);
        assertEquals(
                new ServerSpec(
                        repository.resolve("gradlew").toString(),
                        List.of(
                                "--quiet",
                                "--console=plain",
                                "--no-daemon",
                                "--max-workers=5",
                                ":libtmux-mcp:run",
                                "--args",
                                "--socket-name project")),
                ConfigCodec.read(project, Files.readAllBytes(claude.configPath()), "tmux")
                        .orElseThrow());
        assertTrue(Files.exists(SwapPaths.state(project)));
        assertFalse(Files.exists(SwapPaths.state(user)));

        assertEquals(0, run("revert", "--cli", "claude", "--scope", "project"));
        assertArrayEquals(original, Files.readAllBytes(claude.configPath()));
    }

    @Test
    void abortsWhenAnEffectiveSpecChangesAfterPreflight() throws IOException {
        var claude = clients.getFirst();
        Files.createDirectories(claude.configPath().getParent());
        Files.writeString(claude.configPath(), "{\"projects\":{}}\n", StandardCharsets.UTF_8);
        var human = """
                {"projects":{"%s":{"mcpServers":{"tmux":{
                  "type":"stdio","command":"human","args":[],"env":{"KEEP":"yes"}
                }}}}}
                """.formatted(repository.toAbsolutePath().normalize()).getBytes(StandardCharsets.UTF_8);
        preflight.afterRun = () -> {
            try {
                Files.write(claude.configPath(), human);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        };

        assertEquals(1, run("use", "--source", "gradle", "--cli", "claude"));

        assertArrayEquals(human, Files.readAllBytes(claude.configPath()));
        assertTrue(error().contains("changed after preflight"));
        assertEquals(1, preflight.specs.size());
        try (var paths = Files.walk(home)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains("mcp-swap-java")));
        }
    }

    @Test
    void pathSourcePreflightsAndRecordsTheChosenExecutable() throws IOException {
        seedAll();
        var executable = repository.resolve("build/custom-mcp");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "#!/bin/sh\n", StandardCharsets.UTF_8);
        executable.toFile().setExecutable(true);

        assertEquals(
                0,
                run("use", "--source", "path", "--bin", "build/custom-mcp", "--cli", "codex", "--socket-name", "demo"));

        assertEquals(
                new ServerSpec(executable.toString(), List.of("--socket-name", "demo")), preflight.specs.getFirst());
        assertEquals(
                preflight.specs.getFirst(),
                ConfigCodec.read(
                                clients.get(1),
                                Files.readAllBytes(clients.get(1).configPath()),
                                "tmux")
                        .orElseThrow());
    }

    @Test
    void failedPreflightLeavesConfigsAndRecoveryUntouched() throws IOException {
        var originals = seedAll();
        var before = tree();
        preflight.failure = new IOException("synthetic initialize failure");

        assertEquals(1, run("use", "--source", "gradle", "--cli", "codex"));

        assertTrue(error().contains("synthetic initialize failure"));
        assertArrayEquals(
                originals.get("codex"), Files.readAllBytes(clients.get(1).configPath()));
        assertTreeEquals(before);
    }

    @Test
    void doctorInspectsConfigOnlyClientsAndRedactsAuthEnvironmentValues() throws IOException {
        var codex = clients.get(1);
        Files.createDirectories(codex.configPath().getParent());
        Files.writeString(codex.configPath(), "title = \"keep\"\n", StandardCharsets.UTF_8);
        Files.delete(home.resolve("bin/codex"));
        environment.put("OPENAI_API_KEY", "do-not-print-this-value");
        var before = tree();

        assertEquals(0, run("doctor", "--source", "gradle", "--cli", "codex"));

        assertTrue(output().contains("codex binary missing"));
        assertTrue(output().contains("OPENAI_API_KEY"));
        assertFalse(output().contains("do-not-print-this-value"));
        assertTreeEquals(before);
    }

    @Test
    void doctorReportsOutstandingClaudeScopeWithoutWriting() throws IOException {
        var claude = clients.getFirst();
        Files.createDirectories(claude.configPath().getParent());
        Files.writeString(claude.configPath(), "{\"projects\":{}}\n", StandardCharsets.UTF_8);
        assertEquals(0, run("use", "--source", "gradle", "--cli", "claude"));
        var before = tree();

        stdout.reset();
        assertEquals(0, run("doctor", "--source", "gradle", "--cli", "claude"));

        assertTrue(output().contains("claude:project"));
        assertTrue(output().contains("outstanding swap"));
        assertTreeEquals(before);
    }

    @Test
    void rejectsRetiredAndAmbiguousLauncherArguments() {
        assertEquals(2, run("use", "--safety", "destructive"));
        assertTrue(error().contains("LIBTMUX_SAFETY"));

        stderr.reset();
        assertEquals(2, run("use", "--env", "LIBTMUX_SAFETY=destructive"));
        assertTrue(error().contains("LIBTMUX_SAFETY"));

        stderr.reset();
        assertEquals(2, run("use", "--source", "path"));
        assertTrue(error().contains("--bin"));

        stderr.reset();
        assertEquals(2, run("use", "--socket", "/tmp/s", "--socket-name", "demo"));
        assertTrue(error().contains("mutually exclusive"));
    }

    @Test
    void preflightSeesInheritedSafetyUntilExplicitToolsetsReplacesIt() throws IOException {
        var claude = clients.getFirst();
        Files.createDirectories(claude.configPath().getParent());
        var original = """
                {"mcpServers":{"tmux":{"command":"old","args":[],"env":{
                  "LIBTMUX_SAFETY":"readonly","KEEP":"yes"
                }}}}
                """.getBytes(StandardCharsets.UTF_8);
        Files.write(claude.configPath(), original);
        preflight.failure = new IOException("LIBTMUX_SAFETY is not supported");

        assertEquals(1, run("use", "--source", "gradle", "--cli", "claude", "--scope", "user"));
        assertEquals("readonly", preflight.specs.getFirst().environment().get("LIBTMUX_SAFETY"));
        assertArrayEquals(original, Files.readAllBytes(claude.configPath()));
        assertTrue(error().contains("LIBTMUX_SAFETY"));
        stdout.reset();
        stderr.reset();
        preflight.specs.clear();
        preflight.failure = null;

        assertEquals(
                0,
                run(
                        "use",
                        "--source",
                        "gradle",
                        "--cli",
                        "claude",
                        "--scope",
                        "user",
                        "--env",
                        "LIBTMUX_TOOLSETS=inspect,manage"));

        var effective = preflight.specs.getFirst().environment();
        assertEquals("inspect,manage", effective.get("LIBTMUX_TOOLSETS"));
        assertEquals("yes", effective.get("KEEP"));
        assertFalse(effective.containsKey("LIBTMUX_SAFETY"));
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
                        process,
                        preflight));
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

    private static final class RecordingPreflight implements McpSwap.PreflightRunner {
        private final java.util.ArrayList<ServerSpec> specs = new java.util.ArrayList<>();
        private Runnable afterRun = () -> {};
        private @Nullable IOException failure;

        @Override
        public void run(ServerSpec spec) throws IOException {
            specs.add(spec);
            afterRun.run();
            if (failure != null) {
                throw failure;
            }
        }
    }
}
