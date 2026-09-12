package io.github.libtmux.workspace.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
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

    private static java.util.Map<String, String> inherited(Server server, Path socket) {
        return java.util.Map.of(
                "TMUX",
                socket + "," + server.expand("#{pid}") + ",0",
                "TMUX_PANE",
                server.panes().getFirst().id().value());
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

    @Test
    void failedWindowSetupRetainsItsAutomaticallyCreatedPane() throws Exception {
        Path socket = directory.resolve("partial");
        Path source = directory.resolve("partial.yaml");
        Files.writeString(
                source,
                "session_name: partial\nwindows:\n  - options:\n      not-a-tmux-option: invalid\n    panes: [null]\n");
        try (Server server = server(socket)) {
            try {
                Result failed =
                        invoke("load", source.toString(), "-d", "-S", socket.toString(), "-f", "/dev/null", "--json");
                assertEquals(1, failed.code());
                var effects = new ObjectMapper()
                        .readTree(failed.out())
                        .path("errors")
                        .path(0)
                        .path("effects");
                assertEquals(1, effects.path("window_ids").size());
                assertEquals(1, effects.path("pane_ids").size(), failed.toString());
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
                  - window_name: main
                    window_index: 0
                    layout: even-horizontal
                    panes: [null, null]
                  - window_name: other
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
}
