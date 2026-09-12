package io.github.libtmux.workspace.cli;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
        Files.writeString(source, "session_name: colors\nwindows: [{}]\n");
        try (Server server = server(socket)) {
            try {
                Result result = invoke(
                        java.util.Map.of("LIBTMUX_TEST_TMUX", wrapper.toString()),
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
                assertEquals(2, result.code(), result.toString());
                assertFalse(Files.exists(marker));
                assertFalse(server.sessions().getFirst().options().all().containsKey("@changed"));
                assertEquals(1, server.windows().size());
                assertEquals(
                        "error",
                        new ObjectMapper().readTree(result.out()).path("status").asText());
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
                assertEquals(conflict ? 2 : 0, result.code(), result.toString());
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
                assertReportedObjects(server, failure.path("effects"));
                assertEquals(
                        failRestore,
                        failure.path("effects")
                                .path("renumber_restore_error")
                                .asText()
                                .contains("injected-restore-failure"));
                assertEquals(
                        failRestore ? "off" : "on",
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedSetupReportsEveryCreatedObject(boolean sessionOptions) throws Exception {
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
                var effects = new ObjectMapper()
                        .readTree(failed.out())
                        .path("errors")
                        .path(0)
                        .path("effects");
                assertEquals(1, effects.path("window_ids").size());
                assertEquals(1, effects.path("pane_ids").size(), failed.toString());
                assertReportedObjects(server, effects);
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
}
