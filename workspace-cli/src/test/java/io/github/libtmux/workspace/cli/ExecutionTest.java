package io.github.libtmux.workspace.cli;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.SplitSpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class ExecutionTest {
    @TempDir
    Path directory;

    private record Result(int code, String out, String err) {}

    private Result invoke(String... args) {
        return invoke(java.util.Map.of(), args);
    }

    private Result invoke(java.util.Map<String, String> overrides, String... args) {
        var environment = new HashMap<>(System.getenv());
        environment.remove("TMUX");
        environment.remove("TMUX_PANE");
        environment.remove("COLUMNS");
        environment.remove("LINES");
        environment.remove("TMUXP_DEFAULT_COLUMNS");
        environment.remove("TMUXP_DEFAULT_ROWS");
        environment.remove("TMUXP_DETECT_TERMINAL_SIZE");
        environment.put("HOME", directory.toString());
        environment.put("LIBTMUX_TEST_TMUX", System.getProperty("libtmux.tmux", "tmux"));
        environment.putAll(overrides);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = Main.run(args, environment, directory, InputStream.nullInputStream(), out, err);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Server server(Path socket) {
        return Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .configFile(Path.of("/dev/null"))
                .build();
    }

    private static void assertReportedObjects(Server server, JsonNode effects) {
        assertEquals(
                server.windows().stream().map(window -> window.id().value()).collect(toSet()),
                effects.path("window_ids").valueStream().map(JsonNode::asText).collect(toSet()));
        assertEquals(
                server.panes().stream().map(pane -> pane.id().value()).collect(toSet()),
                effects.path("pane_ids").valueStream().map(JsonNode::asText).collect(toSet()));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void pythonExtensionsUseTheSelectedServerAndReportObservedEffects(boolean append, boolean fail) throws Exception {
        String python = System.getenv("TMUX_WORKSPACE_TEST_PYTHON");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                python != null, "set TMUX_WORKSPACE_TEST_PYTHON for bridge fixtures");
        Path source = directory.resolve("extension.yaml");
        Path socket = directory.resolve("extension-socket");
        Files.writeString(directory.resolve("extensions.py"), """
                import sys
                from tmuxp.workspace.builder.classic import ClassicWorkspaceBuilder
                class Plugin:
                    def before_workspace_builder(self, session): pass
                    def on_window_create(self, window): window.set_option('@plugin', 'called')
                    def on_pane_create(self, pane): pass
                    def after_window_finished(self, window): pass
                    def before_script(self, session): pass
                class Builder(ClassicWorkspaceBuilder):
                    def build(self, session=None, append=False):
                        assert append == self.session_config['custom_append']
                        super().build(session=session, append=append)
                        print('extension-out\\x1b', flush=True)
                        print('extension-err', file=sys.stderr, flush=True)
                        if self.session_config['custom_fail']: raise RuntimeError('extension failed')
                """);
        Files.writeString(
                source,
                "session_name: extension\nworkspace_builder: extensions:Builder\n"
                        + "workspace_builder_paths: [.]\nplugins: [extensions.Plugin]\n"
                        + "custom_append: " + append + "\ncustom_fail: " + fail
                        + "\nwindows:\n  - window_name: built\n    panes: [null, null]\n");
        try (Server server = server(socket)) {
            try {
                var environment = new HashMap<String, String>();
                environment.put("TMUX_WORKSPACE_PYTHON", java.util.Objects.requireNonNull(python));
                String sessionId = "";
                var arguments = new java.util.ArrayList<String>(java.util.List.of("load"));
                if (append) {
                    var original = server.newSession("borrowed");
                    sessionId = original.id().value();
                    environment.putAll(inherited(server, socket));
                    var invoking = original.windows().getFirst();
                    original.newWindow("keep");
                    var other = server.newSession("other");
                    Path move = directory.resolve("move.sh");
                    Files.writeString(
                            move,
                            "exec '" + System.getProperty("libtmux.tmux", "tmux") + "' -S '" + socket
                                    + "' move-window -s '" + invoking.id().value() + "' -t '"
                                    + other.id().value() + ":4'\n");
                    Path first = directory.resolve("first.yaml");
                    Files.writeString(
                            first, "session_name: native-first\nbefore_script: /bin/sh " + move + "\nwindows: [{}]\n");
                    arguments.add(first.toString());
                }
                arguments.addAll(java.util.List.of(
                        source.toString(),
                        append ? "--append" : "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "-s",
                        "~",
                        "--json"));
                Result result = invoke(environment, arguments.toArray(String[]::new));
                assertEquals(fail ? 1 : 0, result.code(), result.toString());
                JsonNode output = new ObjectMapper().readTree(result.out());
                JsonNode effects = fail
                        ? output.path("errors").path(0).path("effects")
                        : output.path("results").path(append ? 1 : 0);
                assertEquals("python", effects.path("engine").asText());
                assertEquals("observed", effects.path("effects_scope").asText());
                assertEquals(
                        append ? "borrowed" : "~", effects.path("session_name").asText());
                assertEquals(
                        "extension-out\u001b\n",
                        effects.path("script_output").path("stdout").asText());
                assertTrue(effects.path("script_output").path("stderr").asText().contains("extension-err"));
                assertEquals(
                        append ? sessionId : server.sessions().getFirst().id().value(),
                        effects.path("session_id").asText());
                var window = server.windows().stream()
                        .filter(value -> value.name().equals("built"))
                        .findFirst()
                        .orElseThrow();
                assertEquals("called", window.options().get("@plugin").orElseThrow());
                if (append) assertEquals(sessionId, window.session().id().value());
                assertEquals(
                        java.util.Set.of(window.id().value()),
                        effects.path("window_ids")
                                .valueStream()
                                .map(JsonNode::asText)
                                .collect(toSet()));
                assertEquals(
                        window.panes().stream().map(pane -> pane.id().value()).collect(toSet()),
                        effects.path("pane_ids")
                                .valueStream()
                                .map(JsonNode::asText)
                                .collect(toSet()));
                assertTrue(server.isAlive(), "extension failure must not trigger native cleanup");
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** Human mode prints a script's output once, raw — not a second, escaped copy after it. */
    @Test
    void shellDashCPrintsOutputOnceInHumanMode() throws Exception {
        String python = System.getenv("TMUX_WORKSPACE_TEST_PYTHON");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                python != null, "set TMUX_WORKSPACE_TEST_PYTHON for bridge fixtures");
        Path socket = directory.resolve("shell-output-socket");
        try (Server server = server(socket)) {
            try {
                server.newSession("target");
                Result result = invoke(
                        java.util.Map.of("TMUX_WORKSPACE_PYTHON", python),
                        "shell",
                        "-S",
                        socket.toString(),
                        "-c",
                        "print('hello'); print('world')",
                        "target");
                assertEquals(0, result.code(), result.err());
                assertTrue(result.out().endsWith("hello\nworld\n"), result.out());
                assertEquals(
                        1,
                        result.out()
                                .lines()
                                .filter(line -> line.equals("hello"))
                                .count(),
                        result.out());
                assertFalse(result.out().contains("Output"), result.out());
                assertFalse(result.out().contains("\\u000a"), result.out());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void customBuilderWithoutWindowsKeepsExpansionAndObservedOwnership() throws Exception {
        String python = System.getenv("TMUX_WORKSPACE_TEST_PYTHON");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                python != null, "set TMUX_WORKSPACE_TEST_PYTHON for bridge fixtures");
        Path source = directory.resolve("existing-extension.yaml");
        Path socket = directory.resolve("existing-extension-socket");
        Files.writeString(directory.resolve("existing_extension.py"), """
                from pathlib import Path
                class Builder:
                    def __init__(self, session_config, server, plugins):
                        assert session_config['start_directory'] == str(Path(__file__).parent)
                        self.session = server.sessions.get(session_name=session_config['custom_session'])
                        self.plugins = plugins
                    def build(self, session=None, append=False): pass
                """);
        Files.writeString(source, """
                session_name: requested
                workspace_builder: existing_extension:Builder
                workspace_builder_paths: [.]
                start_directory: .
                custom_session: existing
                """);
        try (Server server = server(socket)) {
            try {
                var existing = server.newSession("existing");
                Result result = invoke(
                        java.util.Map.of("TMUX_WORKSPACE_PYTHON", java.util.Objects.requireNonNull(python)),
                        "load",
                        source.toString(),
                        "-d",
                        "-S",
                        socket.toString(),
                        "--json");
                assertEquals(0, result.code(), result.toString());
                JsonNode effects = new ObjectMapper()
                        .readTree(result.out())
                        .path("results")
                        .path(0);
                assertEquals(existing.id().value(), effects.path("session_id").asText());
                assertFalse(effects.path("owned_session").asBoolean(), effects.toString());
                assertTrue(effects.path("window_ids").isEmpty(), effects.toString());
                assertTrue(effects.path("pane_ids").isEmpty(), effects.toString());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"--json", "--ndjson"})
    void cancelledPythonExtensionsKeepObservedTopologyAndStopTheirChild(String mode) throws Exception {
        String python = System.getenv("TMUX_WORKSPACE_TEST_PYTHON");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                python != null, "set TMUX_WORKSPACE_TEST_PYTHON for bridge fixtures");
        Path source = directory.resolve("cancel-extension.yaml");
        Path socket = directory.resolve("cancel-extension-socket");
        Path marker = directory.resolve("extension-pid");
        Files.writeString(directory.resolve("cancel_extension.py"), """
                import os, time
                from pathlib import Path
                from tmuxp.workspace.builder.classic import ClassicWorkspaceBuilder
                class Builder(ClassicWorkspaceBuilder):
                    def build(self, session=None, append=False):
                        super().build(session=session, append=append)
                        print('bridge-ready\\x1b', flush=True)
                        Path(self.session_config['marker']).write_text(str(os.getpid()))
                        time.sleep(30)
                """);
        Files.writeString(
                source,
                "session_name: cancelled-extension\nworkspace_builder: cancel_extension:Builder\n"
                        + "workspace_builder_paths: [.]\nmarker: " + marker + "\nwindows: [{}]\n");
        var result = new java.util.concurrent.atomic.AtomicReference<>(new Result(-1, "", ""));
        Thread owner = Thread.ofPlatform()
                .unstarted(() -> result.set(invoke(
                        java.util.Map.of("TMUX_WORKSPACE_PYTHON", java.util.Objects.requireNonNull(python)),
                        "load",
                        source.toString(),
                        "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        mode)));
        try (Server server = server(socket)) {
            try {
                owner.start();
                long deadline =
                        System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
                while (!Files.exists(marker) && owner.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(Files.exists(marker), result.get().toString());
                owner.interrupt();
                owner.join(1_000);
                assertFalse(owner.isAlive(), "Python bridge retained the cancelled invocation");
                assertEquals(130, result.get().code(), result.get().toString());
                String output = result.get().out();
                assertFalse(output.contains("\u001b"), output);
                JsonNode summary = mode.equals("--json")
                        ? new ObjectMapper().readTree(output)
                        : new ObjectMapper()
                                .readTree(output.lines()
                                        .reduce((before, after) -> after)
                                        .orElseThrow());
                assertEquals("partial", summary.path("status").asText(), output);
                JsonNode failure = summary.path("errors").path(0);
                assertEquals("interrupted", failure.path("code").asText());
                assertTrue(failure.path("effects").path("effects_unknown").asBoolean());
                assertReportedObjects(server, failure.path("effects"));
                long pid = Long.parseLong(Files.readString(marker));
                assertFalse(
                        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Python child is still alive");
            } finally {
                owner.interrupt();
                owner.join(2_000);
                if (Files.exists(marker))
                    ProcessHandle.of(Long.parseLong(Files.readString(marker)))
                            .ifPresent(ProcessHandle::destroyForcibly);
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void loadPasses256ColorsToNativeTmuxInvocations() throws Exception {
        Path source = directory.resolve("colors.yaml");
        Path socket = directory.resolve("colors-socket");
        Path trace = directory.resolve("colors-arguments");
        Path wrapper = directory.resolve("tmux-colors");
        Files.writeString(
                wrapper,
                "#!/bin/sh\nprintf '%s\\n' \"$@\" >> '" + trace + "'\nexec '"
                        + System.getProperty("libtmux.tmux", "tmux") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        Files.writeString(
                source,
                "session_name: colors\nplugins: []\nworkspace_builder: null\nworkspace_builder_paths: []\nwindows: [{}]\n");
        try (Server server = server(socket)) {
            try {
                Result result = invoke(
                        java.util.Map.of(
                                "LIBTMUX_TEST_TMUX", wrapper.toString(), "TMUX_WORKSPACE_PYTHON", "/missing/python"),
                        "load",
                        source.toString(),
                        "-d",
                        "-2",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "--json");
                assertEquals(0, result.code(), result.toString());
                assertTrue(Files.readString(trace).lines().anyMatch("-2"::equals), "256-color flag was not sent");
                assertEquals(1, server.windows().size());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** A fresh, still-cold server must not crash asking for a size; COLUMNS/LINES win. */
    @Test
    void loadSizesAColdSessionFromColumnsAndLines() throws Exception {
        Path source = directory.resolve("size.yaml");
        Path socket = directory.resolve("size-socket");
        Files.writeString(source, "session_name: sized\nwindows:\n  - window_name: a\n  - window_name: b\n");
        try (Server server = server(socket)) {
            assumeSizesDetachedSessions(server);
            try {
                assertFalse(server.isAlive(), "the daemon must still be cold before this load");
                Result result = invoke(
                        java.util.Map.of("COLUMNS", "120", "LINES", "40"),
                        "load",
                        source.toString(),
                        "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "--json");
                assertEquals(0, result.code(), result.err());
                for (var window : server.windows())
                    assertEquals(
                            "120x40",
                            window.expand("#{window_width}x#{window_height}"),
                            "window " + window.name() + " was not built at the session size");
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void loadSizesASessionAt80x24WithoutColumnsOrLines() throws Exception {
        Path source = directory.resolve("size-default.yaml");
        Path socket = directory.resolve("size-default-socket");
        Path trace = directory.resolve("size-default-arguments");
        Path wrapper = directory.resolve("tmux-size-default");
        Files.writeString(
                wrapper,
                "#!/bin/sh\nprintf '%s\\n' \"$@\" >> '" + trace + "'\nexec '"
                        + System.getProperty("libtmux.tmux", "tmux") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        Files.writeString(source, "session_name: default-size\nwindows: [{}]\n");
        try (Server server = server(socket)) {
            assumeSizesDetachedSessions(server);
            try {
                Result result = invoke(
                        java.util.Map.of("LIBTMUX_TEST_TMUX", wrapper.toString()),
                        "load",
                        source.toString(),
                        "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "--json");
                assertEquals(0, result.code(), result.err());
                assertTrue(Files.readString(trace).contains("-x\n80\n-y\n24\n"), Files.readString(trace));
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void loadSendsNoSizeWhenDetectionIsDisabled() throws Exception {
        Path source = directory.resolve("size-disabled.yaml");
        Path socket = directory.resolve("size-disabled-socket");
        Path trace = directory.resolve("size-disabled-arguments");
        Path wrapper = directory.resolve("tmux-size-disabled");
        Files.writeString(
                wrapper,
                "#!/bin/sh\nprintf '%s\\n' \"$@\" >> '" + trace + "'\nexec '"
                        + System.getProperty("libtmux.tmux", "tmux") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        Files.writeString(source, "session_name: disabled-size\nwindows: [{}]\n");
        try (Server server = server(socket)) {
            try {
                Result result = invoke(
                        java.util.Map.of(
                                "LIBTMUX_TEST_TMUX", wrapper.toString(),
                                "COLUMNS", "120",
                                "LINES", "40",
                                "TMUXP_DETECT_TERMINAL_SIZE", "0"),
                        "load",
                        source.toString(),
                        "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "--json");
                assertEquals(0, result.code(), result.err());
                assertFalse(Files.readString(trace).lines().anyMatch("-x"::equals), Files.readString(trace));
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void loadRejectsANonNumericColumnsAsUsage() throws Exception {
        Path source = directory.resolve("size-invalid.yaml");
        Path socket = directory.resolve("size-invalid-socket");
        Files.writeString(source, "session_name: invalid-size\nwindows: [{}]\n");
        Result result = invoke(
                java.util.Map.of("COLUMNS", "abc"),
                "load",
                source.toString(),
                "-d",
                "-S",
                socket.toString(),
                "-f",
                "/dev/null",
                "--json");
        assertEquals(2, result.code(), result.toString());
        assertEquals(
                "usage", new ObjectMapper().readTree(result.err()).path("code").asText());
    }

    /** Below 3.3a a size is silently skipped, not sent and ignored, matching {@code SessionSpec}. */
    @Test
    void loadSkipsSizingOnATmuxOlderThan33a() throws Exception {
        Path source = directory.resolve("size-old.yaml");
        Path socket = directory.resolve("size-old-socket");
        Path trace = directory.resolve("size-old-arguments");
        Path wrapper = directory.resolve("tmux-size-old");
        Files.writeString(
                wrapper,
                "#!/bin/sh\nprintf '%s\\n' \"$@\" >> '" + trace
                        + "'\nfor last; do :; done\nif [ \"$last\" = -V ]; then echo 'tmux 3.2a'; exit 0; fi\nexec '"
                        + System.getProperty("libtmux.tmux", "tmux") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        Files.writeString(source, "session_name: old-tmux\nwindows: [{}]\n");
        try (Server server = server(socket)) {
            try {
                assertFalse(server.isAlive());
                Result result = invoke(
                        java.util.Map.of("LIBTMUX_TEST_TMUX", wrapper.toString(), "COLUMNS", "120", "LINES", "40"),
                        "load",
                        source.toString(),
                        "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "--json");
                assertEquals(0, result.code(), result.err());
                assertFalse(Files.readString(trace).lines().anyMatch("-x"::equals), Files.readString(trace));
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"always", "auto"})
    void readinessWaitsUntilACommandConsumerDrawsItsPrompt(String policy) throws Exception {
        Path source = directory.resolve("readiness.yaml");
        Path socket = directory.resolve("readiness-socket");
        Path consumer = directory.resolve("consumer.sh");
        Path marker = directory.resolve("received");
        Path shell = directory.resolve("zsh");
        Files.createSymbolicLink(shell, Path.of("/bin/sh"));
        Files.writeString(consumer, """
                stty -echo
                sleep 0.2
                while IFS= read -r -t 0.02 line; do :; done
                printf READY
                IFS= read -r line
                eval "$line"
                exec sleep 30
                """);
        Files.writeString(
                source,
                "session_name: readiness\nworkspace_builder_options:\n  pane_readiness: " + policy + "\n"
                        + "options:\n  default-shell: " + shell + "\n  default-command: /bin/bash " + consumer
                        + "\nwindows:\n  - panes:\n      - 'printf received > " + marker + "'\n");
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(0, result.code(), result.err());
                long deadline =
                        System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
                while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(Files.exists(marker), "command was discarded before the consumer became ready");
                assertEquals("received", Files.readString(marker));
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void blankPanesAndExplicitLaunchersSkipReadinessQueries() throws Exception {
        Path source = directory.resolve("blank.yaml");
        Path socket = directory.resolve("blank-socket");
        Path trace = directory.resolve("tmux-arguments");
        Path wrapper = directory.resolve("tmux-wrapper");
        String binary = System.getProperty("libtmux.tmux", "tmux");
        Files.writeString(
                wrapper, "#!/bin/sh\nprintf '%s\\n' \"$@\" >> '" + trace + "'\nexec '" + binary + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        try (Server server = server(socket)) {
            try {
                for (String policy : java.util.List.of("always", "auto")) {
                    Files.writeString(
                            source,
                            "session_name: blank-" + policy
                                    + "\nworkspace_builder_options:\n  pane_readiness: " + policy
                                    + "\noptions:\n  default-command: sleep 30\nwindows:\n  - panes: [null, blank]\n");
                    Result result = invoke(
                            java.util.Map.of("LIBTMUX_TEST_TMUX", wrapper.toString()),
                            "load",
                            source.toString(),
                            "-d",
                            "-S",
                            socket.toString(),
                            "-f",
                            "/dev/null",
                            "--json");
                    assertEquals(0, result.code(), result.err());
                }
                Files.writeString(source, """
                        session_name: never
                        workspace_builder_options:
                          pane_readiness: never
                        options:
                          default-command: /bin/cat
                        windows:
                          - panes: ['printed']
                        """);
                assertEquals(
                        0,
                        invoke(
                                        java.util.Map.of("LIBTMUX_TEST_TMUX", wrapper.toString()),
                                        "load",
                                        source.toString(),
                                        "-d",
                                        "-S",
                                        socket.toString(),
                                        "-f",
                                        "/dev/null",
                                        "--json")
                                .code());
                Files.writeString(source, """
                        session_name: launched
                        workspace_builder_options:
                          pane_readiness: always
                        windows:
                          - panes:
                              - shell: /bin/cat
                                shell_command: printed
                        """);
                Result result = invoke(
                        java.util.Map.of("LIBTMUX_TEST_TMUX", wrapper.toString()),
                        "load",
                        source.toString(),
                        "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "--json");
                assertEquals(0, result.code(), result.err());
                String commands = Files.readString(trace);
                assertFalse(commands.contains("#{cursor_x}:#{cursor_y}"), commands);
                assertFalse(commands.contains("default-shell"), commands);
                var launched = server.sessions().stream()
                        .filter(session -> session.name().equals("launched"))
                        .findFirst()
                        .orElseThrow();
                assertEquals(
                        "cat", launched.windows().getFirst().panes().getFirst().currentCommand());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** A stream consumer tracking created objects must not be handed one that is about to go. */
    @Test
    void sessionCreatedReportsNoObjectThatTheLoadThenDestroys() throws Exception {
        Path source = directory.resolve("bootstrap.yaml");
        Path socket = directory.resolve("bootstrap-socket");
        Files.writeString(source, "session_name: bootstrap\nwindows:\n  - window_name: work\n    panes: [{}]\n");
        try (Server server = server(socket)) {
            try {
                Result result = invoke("load", source.toString(), "-d", "-S", socket.toString(), "--ndjson");
                assertEquals(0, result.code(), result.err());
                var events = result.out()
                        .lines()
                        .map(line -> {
                            try {
                                return new ObjectMapper().readTree(line);
                            } catch (java.io.IOException invalid) {
                                throw new java.io.UncheckedIOException(invalid);
                            }
                        })
                        .toList();
                var created = events.stream()
                        .filter(event -> event.path("event").asText().equals("session-created"))
                        .findFirst()
                        .orElseThrow();
                var live = server.windows().stream()
                        .map(window -> window.id().value())
                        .collect(toSet());
                live.addAll(
                        server.panes().stream().map(pane -> pane.id().value()).collect(toSet()));

                assertTrue(
                        live.containsAll(created.path("window_ids")
                                .valueStream()
                                .map(JsonNode::asText)
                                .toList()),
                        created.toString());
                assertTrue(
                        live.containsAll(created.path("pane_ids")
                                .valueStream()
                                .map(JsonNode::asText)
                                .toList()),
                        created.toString());
                assertReportedObjects(
                        server,
                        events.stream()
                                .filter(event -> event.path("event").asText().equals("workspace-completed"))
                                .findFirst()
                                .orElseThrow());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** A live session names itself; nothing about that name is safe to build a path out of. */
    @Test
    void freezeWillNotDeriveADestinationFromTheSession() throws Exception {
        Path socket = directory.resolve("freeze-name-socket");
        try (Server server = server(socket)) {
            try {
                server.newSession(s -> s.named("$HOME"));
                // Releases before 3.5 escape the sigil as they store the name, so ask tmux which
                // spelling it kept rather than assuming the one that went in.
                String stored = server.sessions().stream()
                        .map(session -> session.name())
                        .filter(name -> name.endsWith("HOME"))
                        .findFirst()
                        .orElseThrow();

                Result derived = invoke("freeze", stored, "-S", socket.toString(), "-y", "--quiet");
                Result saved =
                        invoke("freeze", stored, "-S", socket.toString(), "-y", "--save-to", "capture.yaml", "--quiet");

                assertEquals(2, derived.code(), derived.err());
                assertFalse(Files.exists(directory.resolve(stored + ".yaml")));
                assertFalse(Files.exists(Path.of(directory + ".yaml")));
                assertEquals(0, saved.code(), saved.err());
                assertTrue(Files.exists(directory.resolve("capture.yaml")));
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** Freezing a session that does not exist is `session_not_found`. */
    @Test
    void freezeMissingSessionReportsSessionNotFound() throws Exception {
        Path socket = directory.resolve("freeze-missing-socket");
        try (Server server = server(socket)) {
            try {
                server.newSession("present");
                Result result = invoke("freeze", "nosuch", "-S", socket.toString(), "--json");
                assertEquals(1, result.code(), result.toString());
                assertEquals(
                        "session_not_found",
                        new ObjectMapper().readTree(result.err()).path("code").asText());
                assertEquals(
                        "no session named nosuch",
                        new ObjectMapper()
                                .readTree(result.err())
                                .path("message")
                                .asText());
                // A socket with no server behind it holds no session either,
                // so it is the same answer.
                Result cold = invoke("freeze", "nosuch", "-S", socket + ".cold", "--json");
                assertEquals(1, cold.code(), cold.toString());
                assertEquals(
                        "no session named nosuch",
                        new ObjectMapper().readTree(cold.err()).path("message").asText());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /**
     * A tmux command failing while building is `tmux_failed`, in the stderr record and in
     * the load envelope's errors[] alike.
     */
    @Test
    void tmuxCommandFailureWhileBuildingReportsTmuxFailedEverywhere() throws Exception {
        Path source = directory.resolve("tmux-failed.yaml");
        Path socket = directory.resolve("tmux-failed-socket");
        Files.writeString(
                source, "session_name: tf\nwindows:\n  - options:\n      no-such-option-xyz: 1\n    panes: [null]\n");
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(1, result.code(), result.toString());
                assertEquals(
                        "tmux_failed",
                        new ObjectMapper().readTree(result.err()).path("code").asText());
                assertEquals(
                        "tmux_failed",
                        new ObjectMapper()
                                .readTree(result.out())
                                .path("errors")
                                .path(0)
                                .path("code")
                                .asText());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** An existing `--save-to` destination without `--force` is `destination_exists`. */
    @Test
    void freezeExistingDestinationReportsDestinationExists() throws Exception {
        Path socket = directory.resolve("freeze-exists-socket");
        Path destination = directory.resolve("existing.yaml");
        Files.writeString(destination, "sentinel");
        try (Server server = server(socket)) {
            try {
                server.newSession("present");
                Result result = invoke(
                        "freeze",
                        "present",
                        "-S",
                        socket.toString(),
                        "-y",
                        "--save-to",
                        destination.toString(),
                        "--json");
                assertEquals(1, result.code(), result.toString());
                assertEquals(
                        "destination_exists",
                        new ObjectMapper().readTree(result.err()).path("code").asText());
                assertEquals("sentinel", Files.readString(destination));
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** An explicit --save-to is consent; freeze must not prompt with or without --yes. */
    @Test
    void freezeWithSaveToNeedsNoConfirmationEvenWithoutYes() throws Exception {
        Path socket = directory.resolve("freeze-noyes-socket");
        Path destination = directory.resolve("noyes.yaml");
        try (Server server = server(socket)) {
            try {
                server.newSession("present");
                Result result =
                        invoke("freeze", "present", "-S", socket.toString(), "--save-to", destination.toString());
                assertEquals(0, result.code(), result.toString());
                assertTrue(Files.exists(destination));
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** An `x-` key is inert at every level — accepted, ignored, and the session still builds. */
    @Test
    void xPrefixedKeysAreAcceptedAtEveryLevelAndIgnored() throws Exception {
        Path source = directory.resolve("x-keys.yaml");
        Path socket = directory.resolve("x-keys-socket");
        Files.writeString(source, """
                session_name: x-keys
                x-defaults: &shared
                  shell_command_before: [export QA_ANCHOR=1]
                windows:
                  - x-window-note: anything
                    panes:
                      - x-pane-note: anything
                """);
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(0, result.code(), result.err());
                assertEquals(1, server.sessions().size());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** `convert` preserves an `x-` key unchanged rather than dropping or refusing it. */
    @Test
    void convertPreservesXPrefixedKeys() throws Exception {
        Path source = directory.resolve("x-convert.yaml");
        Files.writeString(source, "session_name: x-convert\nx-note: kept\nwindows: []\n");
        Result result = invoke("convert", source.toString(), "--json");
        assertEquals(0, result.code(), result.err());
        assertEquals(
                "kept", new ObjectMapper().readTree(result.out()).path("x-note").asText());
    }

    /** Costs the readiness budget in full: the pane is held at the origin so the poll never wins. */
    @Test
    void readinessTimeoutWarnsAndStillSendsCommands() throws Exception {
        Path source = directory.resolve("timeout.yaml");
        Path socket = directory.resolve("timeout-socket");
        Files.writeString(source, """
                session_name: timeout
                workspace_builder_options:
                  pane_readiness: always
                options:
                  default-command: /bin/cat
                windows:
                  - panes: ['after-timeout']
                """);
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--ndjson");
                assertEquals(0, result.code(), result.err());
                var records = result.out()
                        .lines()
                        .map(line -> {
                            try {
                                return new ObjectMapper().readTree(line);
                            } catch (java.io.IOException error) {
                                throw new AssertionError(error);
                            }
                        })
                        .toList();
                assertTrue(
                        records.stream()
                                .anyMatch(
                                        record -> record.path("event").asText().equals("warning")
                                                && record.path("code").asText().equals("pane_readiness_timeout")),
                        result.out());
                assertEquals("completed", records.getLast().path("event").asText());
                assertTrue(
                        String.join("\n", server.panes().getFirst().capture()).contains("after-timeout"));
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void paneEnterFalseLeavesTheCommandForManualExecution() throws Exception {
        Path source = directory.resolve("typed.yaml");
        Path socket = directory.resolve("typed-socket");
        Path marker = directory.resolve("executed");
        Files.writeString(
                source,
                "session_name: typed\noptions:\n  default-shell: /bin/sh\nwindows:\n  - panes:\n"
                        + "      - enter: false\n        shell_command: printf executed > " + marker + "\n");
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(0, result.code(), result.err());
                var pane = server.panes().getFirst();
                assertTrue(String.join("\n", pane.capture()).contains("printf executed"));
                assertFalse(Files.exists(marker));
                pane.sendKeys(java.util.List.of("Enter"));
                long deadline =
                        System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
                while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals("executed", Files.readString(marker));
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void implicitWindowsReserveLaterExplicitIndexesAndTheMaximumIndex(boolean renumber) throws Exception {
        Path source = directory.resolve("indexes.yaml");
        Path socket = directory.resolve("indexes-socket");
        Files.writeString(source, """
                session_name: indexes
                options:
                  base-index: 3
                  renumber-windows: %s
                windows:
                  - window_name: implicit
                  - window_name: reserved
                    window_index: 3
                  - window_name: maximum
                    window_index: 2147483647
                """.formatted(renumber));
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(0, result.code(), result.err());
                var indexes = server.windows().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                io.github.libtmux.Window::name,
                                window -> window.index().value()));
                assertEquals(java.util.Map.of("implicit", 4, "reserved", 3, "maximum", Integer.MAX_VALUE), indexes);
                assertEquals(
                        renumber ? "on" : "off",
                        server.sessions()
                                .getFirst()
                                .options()
                                .get("renumber-windows")
                                .orElseThrow());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void versionSensitiveLayoutsUseColdAndEmptyEndpointsWithoutCreatingSessions() throws Exception {
        Path socket = directory.resolve("layout-version");
        try (Server server = server(socket)) {
            try {
                String client = server.cmd("-V").stdout().getFirst().replace("tmux ", "");
                boolean mirrors =
                        io.github.libtmux.TmuxVersion.parse(client).atLeast(io.github.libtmux.TmuxVersion.parse("3.5"));
                String layout = mirrors ? "main-horizontal-m" : "main-h";
                String expected = mirrors ? "main-horizontal-mirrored" : "main-horizontal";
                assertEquals(expected, io.github.libtmux.Layouts.require(layout, server, 1));
                assertFalse(server.isAlive(), "cold version lookup must not create a daemon");
                var keeper = server.newSession("keeper");
                server.cmd("set-option", "-s", "exit-empty", "off");
                String pid = server.expand("#{pid}");
                keeper.kill();
                assertEquals(expected, io.github.libtmux.Layouts.require(layout, server, 1));
                assertEquals(pid, server.expand("#{pid}"));
                assertTrue(server.sessions().isEmpty());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void layoutCorpusPreservesAnIndependentSession() throws Exception {
        var mapper = new ObjectMapper();
        try (var resource = ExecutionTest.class.getResourceAsStream("/layout-preflight.json")) {
            assertNotNull(resource);
            var cases = mapper.readTree(resource);
            Path source = directory.resolve("layout.json");
            Path socket = directory.resolve("layout-corpus");
            try (Server server = server(socket)) {
                try {
                    var keeper = server.newSession("keeper");
                    String pid = server.expand("#{pid}");
                    var windows = keeper.windows().stream()
                            .map(io.github.libtmux.Window::id)
                            .toList();
                    var panes = keeper.windows().getFirst().panes().stream()
                            .map(io.github.libtmux.Pane::id)
                            .toList();
                    String version = server.version().atLeast(io.github.libtmux.TmuxVersion.parse("3.5"))
                            ? "3.7c"
                            : server.version().atLeast(io.github.libtmux.TmuxVersion.parse("3.3")) ? "3.3a" : "3.2a";
                    for (var item : cases) {
                        String id = item.path("id").asText();
                        var workspace = mapper.createObjectNode().put("session_name", "layout-" + id);
                        var window = workspace
                                .putArray("windows")
                                .addObject()
                                .put("layout", item.path("layout").asText());
                        var requested = window.putArray("panes");
                        for (int pane = 0; pane < item.path("pane_count").asInt(); pane++) requested.addNull();
                        Files.writeString(source, mapper.writeValueAsString(workspace));
                        Result result = invoke("load", source.toString(), "-d", "-S", socket.toString(), "--json");
                        assertEquals(
                                item.path("expected_valid").path(version).asBoolean(),
                                result.code() == 0,
                                id + ": " + result);
                        assertEquals(pid, server.expand("#{pid}"), id);
                        assertEquals(
                                windows,
                                keeper.refresh().windows().stream()
                                        .map(io.github.libtmux.Window::id)
                                        .toList(),
                                id);
                        assertEquals(
                                panes,
                                keeper.windows().getFirst().panes().stream()
                                        .map(io.github.libtmux.Pane::id)
                                        .toList(),
                                id);
                        for (var session : server.sessions()) if (!session.id().equals(keeper.id())) session.kill();
                    }
                } finally {
                    if (server.isAlive()) server.killServer();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allLayoutsAreCheckedBeforeScriptsOrBorrowedStateChange(boolean append) throws Exception {
        Path first = directory.resolve("first.yaml");
        Path second = directory.resolve("second.yaml");
        Path socket = directory.resolve("layout-socket");
        Path marker = directory.resolve("layout-script");
        Files.writeString(
                first,
                "session_name: first\nbefore_script: /usr/bin/touch " + marker
                        + "\noptions:\n  '@changed': yes\nwindows:\n  - window_name: first\n");
        Files.writeString(
                second, "session_name: second\nwindows:\n  - layout: 'b25d,80x24,0,0,0'\n    panes: [null, null]\n");
        try (Server server = server(socket)) {
            try {
                var keeper = server.newSession("keeper");
                String pid = server.expand("#{pid}");
                var windows = server.windows().stream()
                        .map(io.github.libtmux.Window::id)
                        .toList();
                var panes =
                        server.panes().stream().map(io.github.libtmux.Pane::id).toList();
                Result result = invoke(
                        append ? inherited(server, socket) : java.util.Map.of(),
                        "load",
                        first.toString(),
                        second.toString(),
                        append ? "--append" : "-d",
                        "-S",
                        socket.toString(),
                        "--json");
                assertEquals(1, result.code(), result.toString());
                assertFalse(Files.exists(marker), "no earlier input may run a script");
                assertEquals(pid, server.expand("#{pid}"));
                assertEquals(
                        java.util.List.of(keeper.id()),
                        server.sessions().stream()
                                .map(io.github.libtmux.Session::id)
                                .toList());
                assertEquals(
                        windows,
                        server.windows().stream()
                                .map(io.github.libtmux.Window::id)
                                .toList());
                assertEquals(
                        panes,
                        server.panes().stream().map(io.github.libtmux.Pane::id).toList());
                assertFalse(keeper.options().all().containsKey("@changed"));
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void appendIndexConflictsFailBeforeScriptsOrOptionsRun() throws Exception {
        Path source = directory.resolve("collision.yaml");
        Path socket = directory.resolve("collision-socket");
        Path marker = directory.resolve("script-ran");
        Files.writeString(
                source,
                "session_name: ignored\nbefore_script: /usr/bin/touch " + marker
                        + "\noptions:\n  '@changed': yes\nwindows:\n  - window_index: 0\n");
        try (Server server = server(socket)) {
            try {
                server.newSession("borrowed");
                Result result = invoke(
                        inherited(server, socket),
                        "load",
                        source.toString(),
                        "--append",
                        "-S",
                        socket.toString(),
                        "--json");
                assertEquals(1, result.code(), result.toString());
                assertFalse(Files.exists(marker));
                assertFalse(server.sessions().getFirst().options().all().containsKey("@changed"));
                assertEquals(1, server.windows().size());
                var document = new ObjectMapper().readTree(result.out());
                assertEquals("error", document.path("status").asText());
                assertEquals(
                        "tmux_failed",
                        document.path("errors").path(0).path("code").asText());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void appendReservesLaterFilesBeforeAnyMutation(boolean conflict) throws Exception {
        Path first = directory.resolve("first.yaml");
        Path second = directory.resolve("second.yaml");
        Path socket = directory.resolve("append-files-socket");
        Path marker = directory.resolve("append-script");
        Files.writeString(
                first,
                "session_name: ignored-first\nbefore_script: /usr/bin/touch " + marker
                        + "\noptions:\n  '@changed': yes\nwindows:\n  - window_name: implicit\n");
        Files.writeString(
                second,
                "session_name: ignored-second\nwindows:\n  - window_name: reserved\n    window_index: "
                        + (conflict ? 0 : 1) + "\n");
        try (Server server = server(socket)) {
            try {
                server.newSession("borrowed");
                Result result = invoke(
                        inherited(server, socket),
                        "load",
                        first.toString(),
                        second.toString(),
                        "--append",
                        "-S",
                        socket.toString(),
                        "--json");
                assertEquals(conflict ? 1 : 0, result.code(), result.toString());
                assertEquals(!conflict, Files.exists(marker));
                assertEquals(
                        !conflict, server.sessions().getFirst().options().all().containsKey("@changed"));
                if (conflict) assertEquals(1, server.windows().size());
                else {
                    var indexes = server.windows().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    io.github.libtmux.Window::name,
                                    window -> window.index().value()));
                    assertEquals(2, indexes.get("implicit"));
                    assertEquals(1, indexes.get("reserved"));
                    assertEquals(3, indexes.size());
                }
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void appendKeepsItsBorrowedSessionWhenTheInvokingWindowMoves() throws Exception {
        Path first = directory.resolve("first.yaml");
        Path second = directory.resolve("second.yaml");
        Path script = directory.resolve("move-window.sh");
        Path socket = directory.resolve("stable-append-socket");
        Files.writeString(
                first,
                "session_name: ignored-first\nbefore_script: /bin/sh " + script
                        + "\nwindows:\n  - window_name: first\n");
        Files.writeString(
                second, "session_name: ignored-second\nwindows:\n  - window_name: second\n    window_index: 4\n");
        try (Server server = server(socket)) {
            try {
                var borrowed = server.newSession("borrowed");
                var invoking = borrowed.windows().getFirst();
                borrowed.newWindow("keep");
                var other = server.newSession("other");
                var environment = inherited(server, socket);
                Files.writeString(
                        script,
                        "exec '" + System.getProperty("libtmux.tmux", "tmux") + "' -S '" + socket
                                + "' move-window -s '" + invoking.id().value() + "' -t '"
                                + other.id().value()
                                + ":3'\n");
                Result result = invoke(
                        environment,
                        "load",
                        first.toString(),
                        second.toString(),
                        "--append",
                        "-S",
                        socket.toString(),
                        "--json");
                assertEquals(0, result.code(), result.toString());
                var results = new ObjectMapper().readTree(result.out()).path("results");
                assertEquals(2, results.size());
                for (var item : results)
                    assertEquals(borrowed.id().value(), item.path("session_id").asText(), result.out());
                assertEquals(
                        java.util.Set.of("keep", "first", "second"),
                        borrowed.refresh().windows().stream()
                                .map(io.github.libtmux.Window::name)
                                .collect(toSet()));
                assertEquals(2, other.refresh().windows().size());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** Appending must not move the client unless an appended window sets focus: true. */
    @Test
    void appendDoesNotMoveTheClientUnlessAnAppendedWindowFocuses() throws Exception {
        Path source = directory.resolve("append-focus.yaml");
        Path socket = directory.resolve("append-focus-socket");
        Files.writeString(source, "session_name: ignored\nwindows:\n  - window_name: one\n  - window_name: two\n");
        try (Server server = server(socket)) {
            try {
                var borrowed = server.newSession("borrowed");
                var original = borrowed.windows().getFirst();
                Result result = invoke(
                        inherited(server, socket),
                        "load",
                        source.toString(),
                        "--append",
                        "-S",
                        socket.toString(),
                        "--json");
                assertEquals(0, result.code(), result.toString());
                assertTrue(original.refresh().active(), "append must not move the client off its original window");
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** A script's NDJSON events bracket its output, and each one names its input. */
    @Test
    void scriptEventsBracketTheirOutputAndNameTheirInput() throws Exception {
        Path source = directory.resolve("bracket.yaml");
        Path socket = directory.resolve("bracket-socket");
        Path script = directory.resolve("bracket.sh");
        Files.writeString(script, "#!/bin/sh\nprintf 'from-script\\n'\n");
        assertTrue(script.toFile().setExecutable(true));
        Files.writeString(
                source, "session_name: bracket\nbefore_script: " + script + "\nwindows:\n  - panes: [null]\n");
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--ndjson");
                assertEquals(0, result.code(), result.err());
                var events = new java.util.ArrayList<String>();
                var mapper = new ObjectMapper();
                for (String line : result.out().lines().toList()) {
                    var record = mapper.readTree(line);
                    String event = record.path("event").asText();
                    if (!event.startsWith("script-")) continue;
                    events.add(event);
                    assertEquals(0, record.path("input_index").asInt(-1), line);
                    if (event.equals("script-completed")) {
                        assertEquals(0, record.path("child_status").asInt(-1), line);
                        assertFalse(record.path("truncated").isMissingNode(), line);
                    }
                }
                assertEquals(
                        java.util.List.of("script-started", "script-output", "script-completed"), events, result.out());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** A failing before_script removes the session the load owns, never a borrowed one. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailingBeforeScriptRemovesOnlyTheSessionTheLoadOwns(boolean append) throws Exception {
        Path source = directory.resolve("bsfail.yaml");
        Path socket = directory.resolve("bsfail-socket");
        Files.writeString(source, "session_name: bsfail\nbefore_script: /bin/sh -c 'exit 3'\nwindows: [{}]\n");
        try (Server server = server(socket)) {
            try {
                String borrowedId = append ? server.newSession("borrowed").id().value() : "";
                Result result = invoke(
                        append ? inherited(server, socket) : java.util.Map.of(),
                        "load",
                        source.toString(),
                        append ? "--append" : "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "--json");
                assertEquals(1, result.code(), result.toString());
                assertEquals(
                        "script_failed",
                        new ObjectMapper().readTree(result.err()).path("code").asText());
                assertEquals(
                        "script_failed",
                        new ObjectMapper()
                                .readTree(result.out())
                                .path("errors")
                                .path(0)
                                .path("code")
                                .asText());
                var summary = new ObjectMapper().readTree(result.out());
                var record = summary.path("results").path(0);
                for (String field : java.util.List.of("input", "input_index", "session_id", "session_name", "reused"))
                    assertFalse(record.path(field).isMissingNode(), field + " missing from " + result.out());
                if (!append) assertEquals("error", summary.path("status").asText(), result.out());
                if (append)
                    assertTrue(
                            server.sessions().stream()
                                    .anyMatch(s -> s.id().value().equals(borrowedId)),
                            "a borrowed session must survive a failed before_script");
                else
                    assertTrue(
                            server.sessions().stream().noneMatch(s -> s.name().equals("bsfail")),
                            "the session the failed before_script created must not survive");
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void booleanOptionsUseTmuxValuesWhileEnvironmentRemainsText() throws Exception {
        Path source = directory.resolve("booleans.yaml");
        Path socket = directory.resolve("booleans-socket");
        Files.writeString(source, """
                session_name: booleans
                options:
                  renumber-windows: true
                global_options:
                  mouse: false
                environment:
                  FLAG: true
                windows:
                  - options:
                      remain-on-exit: true
                    options_after:
                      synchronize-panes: false
                """);
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(0, result.code(), result.err());
                var session = server.sessions().getFirst();
                assertEquals("on", session.options().get("renumber-windows").orElseThrow());
                assertEquals("off", server.globalOptions().get("mouse").orElseThrow());
                var options = session.windows().getFirst().options();
                assertEquals("on", options.get("remain-on-exit").orElseThrow());
                assertEquals("off", options.get("synchronize-panes").orElseThrow());
                assertEquals(
                        java.util.List.of("FLAG=true"),
                        server.run(java.util.List.of(
                                        "show-environment", "-t", session.id().value(), "FLAG"))
                                .stdout());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false,false", "false,true,false", "true,true,false", "false,false,true"})
    void bootstrapCleanupPreservesPrimaryFailuresAndSettings(
            boolean failRemoval, boolean failRestore, boolean failDisable) throws Exception {
        Path source = directory.resolve("cleanup.yaml");
        Path socket = directory.resolve("cleanup-socket");
        Path wrapper = directory.resolve("tmux-failed-cleanup");
        Path removing = directory.resolve("removing");
        Files.writeString(wrapper, """
                #!/bin/sh
                case "$*" in
                  %s
                  *kill-window*) : > '%s'; %s;;
                  *renumber-windows*) if test -f '%s'; then %s; fi;;
                esac
                exec '%s' "$@"
                """.formatted(
                        failDisable
                                ? "*set-option*renumber-windows*off*) '%s' \"$@\"; printf injected-disable-failure >&2; exit 1;;"
                                        .formatted(System.getProperty("libtmux.tmux", "tmux"))
                                : "",
                        removing,
                        failRemoval ? "printf injected-cleanup-failure >&2; exit 1" : ":",
                        removing,
                        failRestore ? "printf injected-restore-failure >&2; exit 1" : ":",
                        System.getProperty("libtmux.tmux", "tmux")));
        assertTrue(wrapper.toFile().setExecutable(true));
        Files.writeString(
                source, "session_name: cleanup\noptions:\n  renumber-windows: true\nwindows:\n  - window_index: 4\n");
        try (Server server = server(socket)) {
            try {
                Result result = invoke(
                        java.util.Map.of("LIBTMUX_TEST_TMUX", wrapper.toString()),
                        "load",
                        source.toString(),
                        "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "--json");
                assertEquals(1, result.code());
                var failure =
                        new ObjectMapper().readTree(result.out()).path("errors").path(0);
                assertTrue(failure.path("message")
                        .asText()
                        .contains(
                                failDisable
                                        ? "injected-disable-failure"
                                        : failRemoval ? "injected-cleanup-failure" : "injected-restore-failure"));
                assertEquals("finalize", failure.path("effects").path("stage").asText());
                assertEquals(
                        failRestore,
                        failure.path("effects")
                                .path("renumber_restore_error")
                                .asText()
                                .contains("injected-restore-failure"));
                // The load owned the session, so a cleanup failure takes the whole session with it;
                // what it had created is still listed, because listing it is how the user knows.
                assertFalse(failure.path("effects").path("window_ids").isEmpty(), result.toString());
                assertFalse(failure.path("effects").path("pane_ids").isEmpty(), result.toString());
                assertTrue(failure.path("effects").path("session_removed").asBoolean(), result.toString());
                assertFalse(server.isAlive() && server.hasSession("cleanup"), result.toString());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void bootstrapCleanupPreservesInheritedRenumbering() throws Exception {
        Path source = directory.resolve("inherited-renumber.yaml");
        Path socket = directory.resolve("inherited-renumber-socket");
        Files.writeString(
                source,
                "session_name: inherited\nglobal_options:\n  renumber-windows: true\nwindows:\n  - window_index: 4\n");
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(0, result.code(), result.err());
                assertEquals(4, server.windows().getFirst().index().value());
                server.globalOptions().set("renumber-windows", "off");
                assertEquals(
                        "off",
                        server.sessions()
                                .getFirst()
                                .options()
                                .get("renumber-windows")
                                .orElseThrow());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void loadLogsBothScriptStreamsWithoutContaminatingItsJsonResult() throws Exception {
        Path source = directory.resolve("logged.yaml");
        Path socket = directory.resolve("logged-socket");
        Path log = directory.resolve("operations.jsonl");
        Files.writeString(source, """
                session_name: logged
                before_script: /bin/sh -c 'printf stdout; printf stderr >&2'
                windows:
                  - panes: [null]
                """);
        try (Server server = server(socket)) {
            try {
                Result result = invoke(
                        "load",
                        source.toString(),
                        "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "--json",
                        "--log-file",
                        log.toString(),
                        "--log-level",
                        "info",
                        "--color",
                        "always");
                assertEquals(0, result.code(), result.err());
                assertEquals(
                        "ok",
                        new ObjectMapper().readTree(result.out()).path("status").asText());
                boolean stdout = false;
                boolean stderr = false;
                for (String line : Files.readAllLines(log)) {
                    var record = new ObjectMapper().readTree(line);
                    if (record.path("event").asText().equals("script-output")) {
                        stdout |= record.path("stream").asText().equals("stdout")
                                && record.path("text").asText().equals("stdout");
                        stderr |= record.path("stream").asText().equals("stderr")
                                && record.path("text").asText().equals("stderr");
                    }
                }
                assertTrue(stdout && stderr, Files.readString(log));
                assertFalse(result.out().contains("\u001b") || result.err().contains("\u001b"));
                for (String line : result.err().lines().toList()) new ObjectMapper().readTree(line);
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void humanBootstrapOutputStaysOnItsOriginalStream() throws Exception {
        Path source = directory.resolve("human-output.yaml");
        Path socket = directory.resolve("human-output-socket");
        Files.writeString(source, """
                session_name: human-output
                before_script: /bin/sh -c 'printf "bootstrap-out\\n"; printf "bootstrap-err\\n" >&2'
                windows: [{}]
                """);
        try (Server server = server(socket)) {
            try {
                Result result = invoke(
                        "load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--no-progress");
                assertEquals(0, result.code(), result.toString());
                assertTrue(result.out().contains("bootstrap-out\n"), result.out());
                assertFalse(result.out().contains("bootstrap-err"), result.out());
                assertEquals("bootstrap-err\n", result.err());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void cancellationReleasesChildDrainsAndPreservesPartialJson() throws Exception {
        Path source = directory.resolve("blocked-output.json");
        Path socket = directory.resolve("blocked-output-socket");
        Path script = directory.resolve("bootstrap.sh");
        Path pids = directory.resolve("child-pids");
        Files.writeString(
                script,
                "sleep 30 &\nprintf '%s\\n%s\\n' \"$$\" \"$!\" > '" + pids
                        + "'\nprintf bootstrap-out\nprintf bootstrap-err >&2\nwait\n");
        var config = new ObjectMapper()
                .createObjectNode()
                .put("session_name", "blocked-output")
                .put("before_script", "/bin/sh " + script);
        config.putArray("windows").addObject();
        Files.writeString(source, config.toString());
        var output = new ByteArrayOutputStream();
        var error = new MainTest.BlockedOutput("\"event\":\"script-output\"");
        var status = new java.util.concurrent.atomic.AtomicInteger(-1);
        var environment = new HashMap<>(System.getenv());
        environment.remove("TMUX");
        environment.remove("TMUX_PANE");
        environment.put("HOME", directory.toString());
        environment.put("LIBTMUX_TEST_TMUX", System.getProperty("libtmux.tmux", "tmux"));
        Thread owner = Thread.ofPlatform()
                .unstarted(() -> status.set(Main.run(
                        new String[] {
                            "load",
                            source.toString(),
                            "-d",
                            "-S",
                            socket.toString(),
                            "-f",
                            "/dev/null",
                            "--json",
                            "--log-level",
                            "info"
                        },
                        environment,
                        directory,
                        InputStream.nullInputStream(),
                        output,
                        error)));
        try (Server server = server(socket)) {
            try {
                owner.start();
                assertTrue(error.entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
                owner.interrupt();
                owner.join(1_000);
                assertFalse(owner.isAlive(), "child drains retained the invocation");
                assertEquals(130, status.get());
                var result = new ObjectMapper().readTree(output.toString(StandardCharsets.UTF_8));
                assertTrue(result != null, "missing partial JSON");
                assertEquals("partial", result.path("status").asText());
                var failure = result.path("errors").path(0);
                assertEquals("interrupted", failure.path("code").asText());
                assertReportedObjects(server, failure.path("effects"));
                for (String pid : Files.readAllLines(pids)) {
                    Process probe = new ProcessBuilder("ps", "-o", "stat=", "-p", pid).start();
                    String state = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                    probe.waitFor();
                    assertTrue(state.isEmpty() || state.startsWith("Z"), "owned child is running: " + state);
                }
                assertFalse(error.closed.get());
            } finally {
                error.release.countDown();
                owner.join(2_000);
                assertTrue(error.finished.await(2, java.util.concurrent.TimeUnit.SECONDS));
                if (Files.exists(pids)) {
                    for (String pid : Files.readAllLines(pids))
                        ProcessHandle.of(Long.parseLong(pid)).ifPresent(ProcessHandle::destroyForcibly);
                }
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** Human mode says "Appended" for --append, naming the session that received the windows. */
    @Test
    void humanLoadSummaryNamesAppendedSessionsNotCreated() throws Exception {
        Path source = directory.resolve("append-summary.yaml");
        Path socket = directory.resolve("append-summary-socket");
        Files.writeString(source, "session_name: ignored\nwindows:\n  - window_name: added\n    panes: [null]\n");
        try (Server server = server(socket)) {
            try {
                server.newSession("home");
                Result result = invoke(
                        inherited(server, socket), "load", source.toString(), "--append", "-S", socket.toString());
                assertEquals(0, result.code(), result.err());
                assertTrue(result.out().contains("Appended"), result.out());
                assertTrue(result.out().contains("home"), result.out());
                assertFalse(result.out().contains("Created"), result.out());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    private static java.util.Map<String, String> inherited(Server server, Path socket) {
        return java.util.Map.of(
                "TMUX",
                socket + "," + server.expand("#{pid}") + ",0",
                "TMUX_PANE",
                server.panes().getFirst().id().value());
    }

    /**
     * Every way the invoking context can fail to carry an attached load is refused before the
     * session is built, so a refusal never leaves a workspace running that nobody asked to keep.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {"unparsable-tmux", "not-a-pane-id", "pane-absent", "stale-server", "server-gone", "no-client"})
    void anUnusableInvokingContextBuildsNothing(String broken) throws Exception {
        Path source = directory.resolve("context.yaml");
        Path socket = directory.resolve("context-socket");
        Files.writeString(source, "session_name: probe\nwindows:\n  - panes: [null]\n");
        try (Server server = server(socket)) {
            try {
                server.newSession("host");
                var environment = new HashMap<>(inherited(server, socket));
                switch (broken) {
                    case "unparsable-tmux" -> environment.put("TMUX", socket.toString());
                    case "not-a-pane-id" -> environment.put("TMUX_PANE", "pane-one");
                    case "pane-absent" -> environment.put("TMUX_PANE", "%9999");
                    case "stale-server" -> environment.put("TMUX", socket + ",999999,0");
                    case "server-gone" -> environment.put("TMUX", directory.resolve("gone-socket") + ",1,0");
                    default -> {
                        /* A detached server has the pane but no client on its session. */
                    }
                }
                Result result = invoke(environment, "load", source.toString(), "-S", socket.toString());
                assertEquals(2, result.code(), result.toString());
                assertEquals(1, server.sessions().size(), result.toString());
                assertFalse(server.hasSession("probe"), result.toString());
                assertFalse(result.err().contains("error connecting"), result.err());
                assertFalse(result.err().contains("no current client"), result.err());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** -d beats --append: it builds a new detached session rather than refusing or appending. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detachedBeatsAppend(boolean insideTmux) throws Exception {
        Path source = directory.resolve("t5.yaml");
        Path socket = directory.resolve("t5-socket");
        Files.writeString(source, "session_name: t5\nwindows:\n  - panes: [null]\n");
        try (Server server = server(socket)) {
            try {
                var context = java.util.Map.<String, String>of();
                if (insideTmux) {
                    server.newSession("current");
                    context = inherited(server, socket);
                }
                Result result =
                        invoke(context, "load", source.toString(), "-d", "--append", "-S", socket.toString(), "--json");
                assertEquals(0, result.code(), result.err());
                assertTrue(server.hasSession("t5"), result.err());
                assertEquals(insideTmux ? 2 : 1, server.sessions().size());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** An attached load onto a different server is refused before building, whether or not it runs. */
    @Test
    void attachRefusesADifferentServerTheSameWayRunningOrNot() throws Exception {
        Path currentSocket = directory.resolve("t2-current");
        Path notRunningSocket = directory.resolve("t2-other-not-running");
        Path source = directory.resolve("t2.yaml");
        Files.writeString(source, "session_name: t2\nwindows:\n  - panes: [null]\n");
        try (Server current = server(currentSocket)) {
            try {
                current.newSession("keeper");
                var context = inherited(current, currentSocket);

                Result notRunning = invoke(context, "load", source.toString(), "-S", notRunningSocket.toString());
                assertEquals(2, notRunning.code(), notRunning.toString());
                assertFalse(Files.exists(notRunningSocket), "must refuse before building on the other socket");
                assertTrue(notRunning.err().contains("server"), notRunning.err());
                assertFalse(notRunning.err().contains("terminal"), notRunning.err());

                Path runningSocket = directory.resolve("t2-other-running");
                try (Server other = server(runningSocket)) {
                    try {
                        other.newSession("elsewhere");
                        Result running = invoke(context, "load", source.toString(), "-S", runningSocket.toString());
                        assertEquals(2, running.code(), running.toString());
                        assertEquals(1, other.windows().size(), "must refuse before building on the other server");
                        assertEquals(notRunning.err(), running.err());
                    } finally {
                        if (other.isAlive()) other.killServer();
                    }
                }
            } finally {
                if (current.isAlive()) current.killServer();
            }
        }
    }

    @Test
    void appendAuthenticatesTheDaemonAndAcceptsSocketAliases() throws Exception {
        Path firstSocket = directory.resolve("first");
        Path otherSocket = directory.resolve("other");
        Path alias = directory.resolve("alias");
        Path source = directory.resolve("append.yaml");
        Files.writeString(source, "session_name: ignored\nwindows:\n  - window_name: added\n    panes: [null]\n");
        try (Server first = server(firstSocket);
                Server other = server(otherSocket)) {
            try {
                first.newSession("borrowed");
                other.newSession("unrelated");
                var context = inherited(first, firstSocket);
                Result refused =
                        invoke(context, "load", source.toString(), "--append", "-S", otherSocket.toString(), "--json");
                assertEquals(2, refused.code(), refused.toString());
                assertEquals(1, other.windows().size());
                assertEquals(1, first.windows().size());
                Files.createSymbolicLink(alias, firstSocket);
                Result accepted =
                        invoke(context, "load", source.toString(), "--append", "-S", alias.toString(), "--json");
                assertEquals(0, accepted.code(), accepted.err());
                assertEquals(2, first.windows().size());
            } finally {
                if (first.isAlive()) first.killServer();
                if (other.isAlive()) other.killServer();
            }
        }
    }

    @Test
    void failedAppendReportsTheFirstSuccessfulOptionMutation() throws Exception {
        Path socket = directory.resolve("append");
        Path source = directory.resolve("append.yaml");
        Files.writeString(
                source,
                "session_name: ignored\noptions:\n  '@applied': yes\n  not-a-tmux-option: invalid\nwindows:\n  - panes: [null]\n");
        try (Server server = server(socket)) {
            try {
                server.newSession("borrowed");
                Result failed = invoke(
                        inherited(server, socket),
                        "load",
                        source.toString(),
                        "--append",
                        "-S",
                        socket.toString(),
                        "--json");
                assertEquals(1, failed.code());
                var result = new ObjectMapper().readTree(failed.out());
                assertEquals("partial", result.path("status").asText(), failed.toString());
                assertTrue(
                        server.sessions().getFirst().options().get("@applied").isPresent());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedSetupRemovesTheSessionAndReportsEveryCreatedObject(boolean sessionOptions) throws Exception {
        Path socket = directory.resolve("partial");
        Path source = directory.resolve("partial.yaml");
        Files.writeString(
                source,
                sessionOptions
                        ? "session_name: partial\noptions:\n  not-a-tmux-option: invalid\nwindows: [{}]\n"
                        : "session_name: partial\nwindows:\n  - options:\n      not-a-tmux-option: invalid\n    panes: [null]\n");
        try (Server server = server(socket)) {
            try {
                Result failed =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(1, failed.code());
                var document = new ObjectMapper().readTree(failed.out());
                assertEquals("error", document.path("status").asText(), failed.toString());
                var effects = document.path("errors").path(0).path("effects");
                assertEquals(1, effects.path("window_ids").size());
                assertEquals(1, effects.path("pane_ids").size(), failed.toString());
                assertTrue(effects.path("session_removed").asBoolean(), failed.toString());
                assertFalse(server.isAlive() && server.hasSession("partial"), failed.toString());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /**
     * Readiness is about the shell owning the terminal, not about which shell it is. A pane whose
     * shell never draws is waited for and then reported, where the zsh-only rule sent immediately
     * and said nothing.
     */
    @Test
    void aPaneIsWaitedForWhateverItsShellIs() throws Exception {
        Path source = directory.resolve("readiness.yaml");
        Path socket = directory.resolve("readiness-socket");
        Files.writeString(source, """
                session_name: readiness
                options:
                  default-shell: /bin/cat
                windows:
                  - panes:
                      - echo ready
                """);
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--ndjson");
                assertEquals(0, result.code(), result.err());
                assertTrue(result.out().contains("pane_readiness_timeout"), result.out());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /**
     * A window option written under a session's `options:` reaches every window the document
     * builds, not just the temporary one tmux applies it to and the load then deletes.
     */
    @Test
    void aWindowOptionUnderSessionOptionsReachesEveryWindow() throws Exception {
        Path source = directory.resolve("pane-base-index.yaml");
        Path socket = directory.resolve("pane-base-index-socket");
        Files.writeString(source, """
                session_name: pbi
                options:
                  pane-base-index: 1
                  default-shell: /bin/sh
                windows:
                  - window_name: one
                    panes: [null, null]
                  - window_name: two
                    panes: [null, null]
                """);
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(0, result.code(), result.err());
                assertEquals(
                        java.util.List.of(1, 2, 1, 2),
                        server.panes().stream()
                                .map(io.github.libtmux.Pane::index)
                                .toList(),
                        result.toString());
                assertEquals(
                        "/bin/sh",
                        server.sessions().getFirst().options().all().get("default-shell"),
                        "a session option must still be applied at session scope");
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** An unrecognised builder option is said and ignored, never a refusal of the whole document. */
    @Test
    void anUnknownBuilderOptionWarnsAndLoads() throws Exception {
        Path source = directory.resolve("builder-options.yaml");
        Path socket = directory.resolve("builder-options-socket");
        Files.writeString(source, """
                session_name: builder
                workspace_builder_options:
                  pane_readiness: never
                  not_a_builder_option: 1
                windows:
                  - panes: [null]
                """);
        try (Server server = server(socket)) {
            try {
                Result result =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--ndjson");
                assertEquals(0, result.code(), result.err());
                assertTrue(result.out().contains("not_a_builder_option"), result.out());
                assertTrue(server.hasSession("builder"), result.toString());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /**
     * Child containment resolves its helpers where the invocation says, and a helper that is not
     * installed leaves the containment off rather than failing a script that succeeded.
     */
    @Test
    void childContainmentResolvesItsHelpersOnPath() throws Exception {
        Path bin = Files.createDirectories(directory.resolve("bin"));
        Path complaining = bin.resolve("ps");
        Files.writeString(complaining, "#!/bin/sh\nprintf 'no process table here\\n' >&2\nexit 1\n");
        assertTrue(complaining.toFile().setExecutable(true));
        Path empty = Files.createDirectories(directory.resolve("empty-bin"));
        Path source = directory.resolve("contained.yaml");
        Path socket = directory.resolve("contained-socket");
        Files.writeString(source, """
                session_name: contained
                before_script: /bin/sh -c 'exit 0'
                windows:
                  - panes: [null]
                """);
        var context = new Main.Context(
                new HashMap<>(System.getenv()),
                directory,
                InputStream.nullInputStream(),
                java.io.OutputStream.nullOutputStream(),
                java.io.OutputStream.nullOutputStream());
        String tmux = Children.executable(context, System.getProperty("libtmux.tmux", "tmux"));
        try (Server server = server(socket)) {
            try {
                Result refused = invoke(
                        java.util.Map.of("PATH", bin.toString(), "LIBTMUX_TEST_TMUX", tmux),
                        "load",
                        source.toString(),
                        "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "--json");
                assertEquals(1, refused.code(), refused.toString());
                assertTrue(refused.err().contains("no process table here"), refused.err());

                Result absent = invoke(
                        java.util.Map.of("PATH", empty.toString(), "LIBTMUX_TEST_TMUX", tmux),
                        "load",
                        source.toString(),
                        "-d",
                        "-S",
                        socket.toString(),
                        "-f",
                        "/dev/null",
                        "--json");
                assertEquals(0, absent.code(), absent.toString());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** A capture never writes a document a load refuses: both ends apply one name rule. */
    @Test
    void freezeRefusesASessionNameLoadWouldNotRead() throws Exception {
        Path socket = directory.resolve("freeze-name-socket");
        Path destination = directory.resolve("frozen.yaml");
        try (Server server = server(socket)) {
            try {
                server.newSession("my.proj");
                Result refused = invoke(
                        "freeze", "my.proj", "-S", socket.toString(), "--save-to", destination.toString(), "--json");
                assertEquals(1, refused.code(), refused.toString());
                assertEquals(
                        "invalid_workspace",
                        new ObjectMapper().readTree(refused.err()).path("code").asText(),
                        refused.err());
                assertFalse(Files.exists(destination), refused.toString());

                server.newSession("plain");
                Result captured = invoke(
                        "freeze", "plain", "-S", socket.toString(), "--save-to", destination.toString(), "--json");
                assertEquals(0, captured.code(), captured.toString());
                Result reloaded = invoke("load", destination.toString(), "-d", "-S", socket.toString(), "--json");
                assertEquals(0, reloaded.code(), reloaded.toString());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** Reuse is a comparison: a running session missing a window the document asks for is not "ok". */
    @Test
    void reusingASessionMissingADocumentWindowIsNotReportedAsSuccess() throws Exception {
        Path source = directory.resolve("reuse.yaml");
        Path socket = directory.resolve("reuse-socket");
        Files.writeString(source, """
                session_name: reuse
                windows:
                  - window_name: one
                    panes: [null]
                  - window_name: two
                    panes: [null]
                """);
        try (Server server = server(socket)) {
            try {
                var session = server.newSession("reuse");
                session.windows().getFirst().rename("one");
                Result missing =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(1, missing.code(), missing.toString());
                var document = new ObjectMapper().readTree(missing.out());
                assertEquals("partial", document.path("status").asText(), missing.toString());
                assertTrue(
                        document.path("errors").path(0).path("message").asText().contains("two"), missing.toString());
                assertEquals(1, server.windows().size(), missing.toString());

                session.refresh().newWindow("two");
                Result complete =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(0, complete.code(), complete.toString());
                assertEquals(
                        "ok",
                        new ObjectMapper()
                                .readTree(complete.out())
                                .path("status")
                                .asText(),
                        complete.toString());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** An append that fails partway keeps what it added, and the failure says which windows those are. */
    @Test
    void aFailedAppendNamesTheWindowsItKeeps() throws Exception {
        Path source = directory.resolve("append-kept.yaml");
        Path socket = directory.resolve("append-kept-socket");
        Files.writeString(source, """
                session_name: ignored
                windows:
                  - window_name: kept
                    panes: [null]
                  - window_name: broken
                    options:
                      not-a-tmux-option: invalid
                    panes: [null]
                """);
        try (Server server = server(socket)) {
            try {
                server.newSession("borrowed");
                Result failed = invoke(
                        inherited(server, socket),
                        "load",
                        source.toString(),
                        "--append",
                        "-S",
                        socket.toString(),
                        "--json");
                assertEquals(1, failed.code(), failed.toString());
                var document = new ObjectMapper().readTree(failed.out());
                assertEquals("partial", document.path("status").asText(), failed.toString());
                String message = document.path("errors").path(0).path("message").asText();
                assertTrue(message.contains("windows kept:"), failed.toString());
                assertTrue(message.contains("kept"), failed.toString());
                assertTrue(
                        server.windows().stream()
                                .anyMatch(window -> window.name().equals("kept")),
                        failed.toString());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** Human mode says which a load did, one session at a time, not "1 workspaces" either way. */
    @Test
    void humanLoadSummaryDistinguishesCreatedFromReused() throws Exception {
        Path source = directory.resolve("d4.yaml");
        Path socket = directory.resolve("d4-socket");
        Files.writeString(source, "session_name: d4\nwindows: [{}]\n");
        try (Server server = server(socket)) {
            try {
                Result created = invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null");
                assertEquals(0, created.code(), created.err());
                assertTrue(created.out().contains("Created"), created.out());
                assertTrue(created.out().contains("d4"), created.out());
                assertFalse(created.out().contains("workspaces"), created.out());

                Result reused = invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null");
                assertEquals(0, reused.code(), reused.err());
                assertTrue(reused.out().contains("Reused"), reused.out());
                assertTrue(reused.out().contains("d4"), reused.out());
                assertFalse(reused.out().contains("workspaces"), reused.out());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void coldLoadCaptureAndReusePreserveIndexedTopology() throws Exception {
        Path socket = directory.resolve("socket");
        Path source = directory.resolve("workspace.yaml");
        Files.writeString(source, """
                session_name: native-java
                options:
                  default-shell: /bin/sh
                environment:
                  WORKSPACE_TEST: inherited
                windows:
                  - &main
                    window_name: main
                    window_index: 0
                    layout: even-horizontal
                    panes: [null, null]
                  - <<: *main
                    window_name: other
                    window_index: 4
                    panes: [null]
                """);
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            try {
                assertFalse(server.isAlive());
                Result load =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--ndjson");
                assertEquals(0, load.code(), load.err());
                var events = load.out()
                        .lines()
                        .map(line -> {
                            try {
                                return new ObjectMapper().readTree(line);
                            } catch (java.io.IOException invalid) {
                                throw new java.io.UncheckedIOException(invalid);
                            }
                        })
                        .toList();
                assertEquals("started", events.getFirst().path("event").asText());
                assertEquals("completed", events.getLast().path("event").asText());
                assertEquals(2, server.windows().size());
                assertEquals(3, server.panes().size());
                assertEquals(
                        java.util.List.of(0, 4),
                        server.windows().stream()
                                .map(window -> window.index().value())
                                .toList());
                Result freeze = invoke("freeze", "native-java", "-S", socket.toString(), "--json");
                assertEquals(0, freeze.code(), freeze.err());
                var captured = new ObjectMapper().readTree(freeze.out());
                assertEquals(2, captured.path("windows").size());
                assertEquals(
                        4, captured.path("windows").path(1).path("window_index").asInt());
                Result reused = invoke("load", source.toString(), "-d", "-S", socket.toString(), "--json");
                assertEquals(0, reused.code(), reused.err());
                assertTrue(new ObjectMapper()
                        .readTree(reused.out())
                        .path("results")
                        .path(0)
                        .path("reused")
                        .asBoolean());
                assertEquals(2, server.windows().size());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** With no start_directory anywhere, panes must start in the invocation directory, not the document's. */
    @Test
    void defaultPaneDirectoryIsTheInvocationDirectoryNotTheDocumentDirectory() throws Exception {
        Path socket = directory.resolve("h9-socket");
        Path documentDirectory = Files.createDirectories(directory.resolve("docs"));
        Path source = documentDirectory.resolve("h9.yaml");
        Files.writeString(source, "session_name: h9\nwindows:\n  - panes: [null]\n");
        try (Server server = server(socket)) {
            try {
                Result result = invoke("load", source.toString(), "-d", "-S", socket.toString(), "--json");
                assertEquals(0, result.code(), result.err());
                String cwd = server.panes().getFirst().currentPath().toString();
                assertEquals(directory.toString(), cwd, cwd);
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** A relative start_directory resolves against the parent's own directory when it declared one. */
    @Test
    void windowRelativeStartDirectoryResolvesAgainstTheSessionsExplicitDirectory() throws Exception {
        Path socket = directory.resolve("t11-socket");
        Path documentDirectory = Files.createDirectories(directory.resolve("docs"));
        Path sessionDirectory = Files.createDirectories(directory.resolve("elsewhere"));
        Files.createDirectories(sessionDirectory.resolve("sub/x"));
        Path source = documentDirectory.resolve("t11.yaml");
        Files.writeString(
                source,
                "session_name: t11\nstart_directory: " + sessionDirectory
                        + "\nwindows:\n  - start_directory: ./sub\n    panes:\n      - start_directory: ./x\n");
        try (Server server = server(socket)) {
            try {
                Result result = invoke("load", source.toString(), "-d", "-S", socket.toString(), "--json");
                assertEquals(0, result.code(), result.err());
                String cwd = server.panes().getFirst().currentPath().toString();
                assertEquals(sessionDirectory.resolve("sub/x").toString(), cwd, cwd);
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** With no session start_directory, a window's relative value falls back to the document directory. */
    @Test
    void windowRelativeStartDirectoryFallsBackToTheDocumentDirectoryWithNoSessionDirectory() throws Exception {
        Path socket = directory.resolve("t11-fallback-socket");
        Path documentDirectory = Files.createDirectories(directory.resolve("docroot"));
        Files.createDirectories(documentDirectory.resolve("sub"));
        Path source = documentDirectory.resolve("t11b.yaml");
        Files.writeString(source, "session_name: t11b\nwindows:\n  - start_directory: ./sub\n    panes: [null]\n");
        try (Server server = server(socket)) {
            try {
                Result result = invoke("load", source.toString(), "-d", "-S", socket.toString(), "--json");
                assertEquals(0, result.code(), result.err());
                String cwd = server.panes().getFirst().currentPath().toString();
                assertEquals(documentDirectory.resolve("sub").toString(), cwd, cwd);
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** Human-mode warnings are one labelled sentence, not the machine event name plus its JSON record. */
    @Test
    void humanWarningsPrintOneLabelAndASentenceNotTheMachineRecord() throws Exception {
        Path source = directory.resolve("missing-dir.yaml");
        Path socket = directory.resolve("missing-dir-socket");
        Path missing = directory.resolve("does-not-exist");
        Files.writeString(
                source, "session_name: missing-dir\nstart_directory: " + missing + "\nwindows:\n  - panes: [null]\n");
        try (Server server = server(socket)) {
            try {
                Result result = invoke("load", source.toString(), "-d", "-S", socket.toString());
                assertEquals(0, result.code(), result.err());
                assertFalse(result.err().contains("warning  warning"), result.err());
                assertFalse(result.err().contains("{\"code\""), result.err());
                assertTrue(
                        result.err()
                                .contains("Warning  start_directory is not a directory, tmux will fall back to $HOME: "
                                        + missing),
                        result.err());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** freeze must omit shell_command for the session's own default shell and emit it for anything else. */
    @Test
    void freezeOmitsShellCommandOnlyForTheSessionDefaultShell() throws Exception {
        Path socket = directory.resolve("freeze-shell-socket");
        try (Server server = server(socket)) {
            try {
                Session session =
                        server.newSession(s -> s.named("qa-freeze-shell").in(directory));
                session.windows()
                        .getFirst()
                        .panes()
                        .getFirst()
                        .split(SplitSpec.builder()
                                .below()
                                .detached()
                                .running("cat")
                                .build());
                Result result = invoke("freeze", "qa-freeze-shell", "-S", socket.toString(), "-y", "--json", "--quiet");
                assertEquals(0, result.code(), result.err());
                var panes = new ObjectMapper()
                        .readTree(result.out())
                        .path("windows")
                        .path(0)
                        .path("panes");
                assertEquals(2, panes.size(), panes.toString());
                assertFalse(panes.path(0).has("shell_command"), panes.toString());
                assertEquals("cat", panes.path(1).path("shell_command").path(0).asText(), panes.toString());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /**
     * pane-created, pane-completed, window-created and window-completed must carry input_index,
     * session_id and the document ordinal, not just the tmux ids.
     */
    @Test
    void ndjsonPaneAndWindowEventsCarryInputIndexSessionIdAndOrdinal() throws Exception {
        Path socket = directory.resolve("ndjson-fields-socket");
        Path source = directory.resolve("ndjson-fields.yaml");
        Files.writeString(source, "session_name: ndjson-fields\nwindows:\n  - panes: [null, null]\n");
        try (Server server = server(socket)) {
            try {
                Result result = invoke("load", source.toString(), "-d", "-S", socket.toString(), "--ndjson");
                assertEquals(0, result.code(), result.err());
                String sessionId = server.sessions().getFirst().id().value();
                int windowIndex = server.windows().getFirst().index().value();
                var events = result.out()
                        .lines()
                        .map(line -> {
                            try {
                                return new ObjectMapper().readTree(line);
                            } catch (java.io.IOException invalid) {
                                throw new java.io.UncheckedIOException(invalid);
                            }
                        })
                        .toList();
                for (String name : java.util.List.of("window-created", "window-completed")) {
                    var event = events.stream()
                            .filter(candidate ->
                                    candidate.path("event").asText().equals(name))
                            .findFirst()
                            .orElseThrow();
                    assertEquals(0, event.path("input_index").asInt(), event.toString());
                    assertEquals(sessionId, event.path("session_id").asText(), event.toString());
                    assertEquals(windowIndex, event.path("window_index").asInt(), event.toString());
                }
                for (String name : java.util.List.of("pane-created", "pane-completed")) {
                    var matching = events.stream()
                            .filter(candidate ->
                                    candidate.path("event").asText().equals(name))
                            .toList();
                    assertEquals(2, matching.size(), matching.toString());
                    for (int index = 0; index < matching.size(); index++) {
                        var event = matching.get(index);
                        assertEquals(0, event.path("input_index").asInt(), event.toString());
                        assertEquals(sessionId, event.path("session_id").asText(), event.toString());
                        assertTrue(event.has("window_id"), event.toString());
                        assertEquals(index, event.path("pane_index").asInt(), event.toString());
                    }
                }
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** tmux 3.2a accepts -x/-y for a detached session and ignores them, so the CLI sends no size there. */
    private static void assumeSizesDetachedSessions(Server server) {
        String client = server.cmd("-V").stdout().getFirst().replace("tmux ", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                io.github.libtmux.TmuxVersion.parse(client).atLeast(io.github.libtmux.TmuxVersion.parse("3.3a")),
                "detached session sizing needs tmux 3.3a");
    }
}
