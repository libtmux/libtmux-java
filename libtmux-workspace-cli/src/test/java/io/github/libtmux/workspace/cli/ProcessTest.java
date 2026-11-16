package io.github.libtmux.workspace.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Drives the installed launcher as a real process, which is why the whole class is tagged. */
@org.junit.jupiter.api.Tag("distribution")
final class ProcessTest {
    @TempDir
    Path directory;

    /** Kept apart from {@code directory}, which some tests list or run the launcher in. */
    @TempDir
    Path programs;

    /**
     * Copies a Python program from the test resources, and the pty helper it imports, into one
     * directory and returns the program: Python puts a script's own directory on its import path.
     */
    private Path program(String name) throws IOException {
        copyResource("/io/github/libtmux/fixtures/pty_helper.py", programs.resolve("pty_helper.py"));
        Path program = programs.resolve(name + ".py");
        copyResource("/io/github/libtmux/workspace/cli/probes/" + name + ".py", program);
        return program;
    }

    private static void copyResource(String resource, Path target) throws IOException {
        try (var source = ProcessTest.class.getResourceAsStream(resource)) {
            if (source == null) throw new IllegalStateException("missing test resource " + resource);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private ProcessBuilder command(String... args) {
        var argv = new ArrayList<>(List.of(System.getProperty("workspace.cli.launcher")));
        argv.addAll(List.of(args));
        var process = new ProcessBuilder(argv).directory(directory.toFile());
        process.environment().remove("TMUX");
        process.environment().remove("TMUX_PANE");
        process.environment().put("HOME", directory.toString());
        process.environment().put("LIBTMUX_TEST_TMUX", System.getProperty("libtmux.tmux", "tmux"));
        return process;
    }

    @Test
    void generatedCompletionKeepsEachChoiceInTheActualBashEditor() throws Exception {
        Path completion = directory.resolve("completion.bash");
        Process generator = command("--generate", "bash")
                .redirectOutput(completion.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            assertTrue(generator.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, generator.exitValue());
        } finally {
            if (generator.isAlive()) generator.destroyForcibly().waitFor();
        }
        Process probe = new ProcessBuilder(
                        "python3", program("bash_completion").toString(), directory.toString(), completion.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            assertTrue(probe.waitFor(45, TimeUnit.SECONDS));
            assertEquals(0, probe.exitValue());
        } finally {
            if (probe.isAlive()) {
                var descendants = probe.descendants().toList();
                descendants.forEach(ProcessHandle::destroyForcibly);
                try {
                    for (var descendant : descendants) descendant.onExit().get(2, TimeUnit.SECONDS);
                } finally {
                    probe.destroyForcibly().waitFor();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tmuxinator", "teamocil"})
    void installedImportsLoadCommandsInOrderWithNativeFocusAndOptions(String kind) throws Exception {
        Path socket = directory.resolve("import-socket");
        Path project =
                Files.createDirectories(directory.resolve("project/child")).toRealPath();
        Path source = Files.createDirectories(directory.resolve("inputs")).resolve("source.json");
        Path saved = Files.createDirectories(directory.resolve("moved")).resolve("native.json");
        Path out = directory.resolve("output.json");
        Path err = directory.resolve("error.log");
        Path before = directory.resolve("before");
        Path suppressed = directory.resolve("suppressed");
        var mapper = new ObjectMapper();
        ObjectNode document = mapper.createObjectNode().put("name", "imported");
        var windows = document.putArray("windows");
        if (kind.equals("tmuxinator")) {
            document.put("root", "project");
            document.putArray("pre_window").add("false").add("printf b >> '" + before + "'");
            var main = windows.addObject()
                    .putObject("main")
                    .put("root", "child")
                    .put("layout", "even-horizontal")
                    .put("synchronize", "after");
            main.putArray("pre").add("false").add("printf lost > '" + suppressed + "'");
            var panes = main.putArray("panes");
            for (int index = 0; index < 3; index++) {
                panes.addArray()
                        .add("IMPORT_VALUE=pane" + index)
                        .add("printf '%s:' \"$IMPORT_VALUE\" > '" + directory.resolve("marker" + index) + "'; pwd >> '"
                                + directory.resolve("marker" + index) + "'");
            }
            windows.addObject()
                    .putArray("sequence")
                    .add("IMPORT_SEQUENCE=one")
                    .add("printf '%s-two' \"$IMPORT_SEQUENCE\" > '" + directory.resolve("sequence") + "'");
        } else {
            var main = windows.addObject()
                    .put("name", "main")
                    .put("root", "project/child")
                    .put("layout", "even-horizontal");
            main.putObject("options").put("automatic-rename", false).put("@imported", "retained");
            var panes = main.putArray("panes");
            for (int index = 0; index < 3; index++) {
                panes.addObject()
                        .put("focus", index > 0)
                        .putArray("commands")
                        .add("false")
                        .add("printf 'pane" + index + ":' > '" + directory.resolve("marker" + index) + "'; pwd >> '"
                                + directory.resolve("marker" + index) + "'");
            }
            windows.addObject()
                    .put("name", "sequence")
                    .put("focus", true)
                    .putArray("splits")
                    .addObject()
                    .put("cmd", "printf one-two > '" + directory.resolve("sequence") + "'");
            windows.addObject()
                    .put("name", "later")
                    .put("focus", true)
                    .putArray("panes")
                    .addNull();
        }
        Files.writeString(source, mapper.writeValueAsString(document));
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .configFile(Path.of("/dev/null"))
                .build()) {
            Process process = null;
            try {
                var keeper = server.newSession("keeper");
                String keeperId = keeper.id().value();
                var keeperPanes = server.cmd("list-panes", "-t", keeperId, "-F", "#{pane_id}:#{pane_pid}")
                        .stdout();
                server.globalOptions().set("default-shell", "/bin/sh");
                for (String[] args : List.of(
                        new String[] {
                            "import",
                            kind,
                            source.toString(),
                            "--json",
                            "--workspace-format",
                            "json",
                            "--save-to",
                            saved.toString()
                        },
                        new String[] {"load", saved.toString(), "-d", "-S", socket.toString(), "--json"})) {
                    process = command(args)
                            .redirectOutput(out.toFile())
                            .redirectError(err.toFile())
                            .start();
                    assertTrue(process.waitFor(45, TimeUnit.SECONDS), "installed import/load did not finish");
                    assertEquals(0, process.exitValue(), Files.readString(err));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    Path sequence = directory.resolve("sequence");
                    boolean complete =
                            Files.exists(sequence) && Files.readString(sequence).equals("one-two");
                    for (int index = 0; index < 3; index++) {
                        Path marker = directory.resolve("marker" + index);
                        complete &= Files.exists(marker)
                                && Files.readString(marker).equals("pane" + index + ":" + project + "\n");
                    }
                    if (kind.equals("tmuxinator"))
                        complete &=
                                Files.exists(before) && Files.readString(before).equals("bbbb");
                    if (complete) break;
                    Thread.sleep(20);
                }
                for (int index = 0; index < 3; index++) {
                    assertEquals(
                            "pane" + index + ":" + project + "\n",
                            Files.readString(directory.resolve("marker" + index)));
                }
                assertEquals("one-two", Files.readString(directory.resolve("sequence")));
                var loaded = server.session("imported").orElseThrow();
                var main = loaded.windows().getFirst();
                var nativePanes = server.cmd(
                                "list-panes",
                                "-t",
                                main.id().value(),
                                "-F",
                                "#{pane_id}:#{pane_index}:#{pane_left}:#{pane_active}")
                        .stdout();
                assertEquals(3, nativePanes.size());
                var effects = mapper.readTree(Files.readString(out))
                        .path("results")
                        .path(0)
                        .path("pane_ids");
                int previousLeft = -1;
                for (int index = 0; index < 3; index++) {
                    String[] fields = nativePanes.get(index).split(":", -1);
                    assertEquals(effects.path(index).asText(), fields[0]);
                    assertEquals(index, Integer.parseInt(fields[1]));
                    assertTrue(Integer.parseInt(fields[2]) > previousLeft);
                    previousLeft = Integer.parseInt(fields[2]);
                    assertEquals(index == (kind.equals("tmuxinator") ? 0 : 1) ? "1" : "0", fields[3]);
                }
                String expectedWindow = kind.equals("tmuxinator")
                        ? main.id().value()
                        : loaded.windows().get(1).id().value();
                assertEquals(
                        List.of(expectedWindow),
                        server.cmd("display-message", "-p", "-t", loaded.id().value(), "#{window_id}")
                                .stdout());
                if (kind.equals("tmuxinator")) {
                    assertEquals("bbbb", Files.readString(before));
                    assertFalse(Files.exists(suppressed));
                    assertEquals("on", main.options().get("synchronize-panes").orElseThrow());
                    Path synchronizedOutput = directory.resolve("synchronized");
                    assertTrue(server.cmd(
                                    "send-keys",
                                    "-t",
                                    effects.path(0).asText(),
                                    "-l",
                                    "--",
                                    "printf x >> '" + synchronizedOutput + "'")
                            .succeeded());
                    assertTrue(server.cmd("send-keys", "-t", effects.path(0).asText(), "Enter")
                            .succeeded());
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (System.nanoTime() < deadline
                            && (!Files.exists(synchronizedOutput) || Files.size(synchronizedOutput) < 3))
                        Thread.sleep(20);
                    assertEquals("xxx", Files.readString(synchronizedOutput));
                } else {
                    assertEquals("off", main.options().get("automatic-rename").orElseThrow());
                    assertEquals("retained", main.options().get("@imported").orElseThrow());
                }
                assertEquals(keeperId, keeper.refresh().id().value());
                assertEquals(
                        keeperPanes,
                        server.cmd("list-panes", "-t", keeperId, "-F", "#{pane_id}:#{pane_pid}")
                                .stdout());
            } finally {
                if (process != null && process.isAlive())
                    process.destroyForcibly().waitFor();
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void installedCaptureReloadPreservesOptionLineEndings() throws Exception {
        Path socket = directory.resolve("capture-socket");
        Path captured = directory.resolve("captured.json");
        Path output = directory.resolve("result.json");
        Path error = directory.resolve("error.log");
        var values = java.util.Map.of(
                "@cr", "first\rsecond",
                "@cr*", "one star",
                "@cr**", "two stars",
                "@crlf", "first\r\nsecond\r",
                "@mixed", "\r\nfirst\rsecond\n\n",
                "@cr-only", "\r");
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .configFile(Path.of("/dev/null"))
                .build()) {
            Process process = null;
            try {
                var keeper = server.newSession("keeper");
                var keeperWindow = keeper.windows().getFirst();
                var source = server.newSession("source");
                var sourceWindow = source.windows().getFirst();
                values.forEach(source.options()::set);
                values.forEach(sourceWindow.options()::set);

                process = command("freeze", "source", "-S", socket.toString(), "--json")
                        .redirectOutput(captured.toFile())
                        .redirectError(error.toFile())
                        .start();
                assertTrue(process.waitFor(45, TimeUnit.SECONDS), "capture did not finish");
                assertEquals(0, process.exitValue(), Files.readString(error));
                var mapper = new ObjectMapper();
                var document = (ObjectNode) mapper.readTree(Files.readString(captured));
                values.forEach((name, value) -> {
                    assertEquals(value, document.path("options").path(name).asText(), name);
                    assertEquals(
                            value,
                            document.path("windows")
                                    .path(0)
                                    .path("options_after")
                                    .path(name)
                                    .asText(),
                            name);
                });
                document.put("session_name", "restored");
                Files.writeString(captured, mapper.writeValueAsString(document));
                process = command("load", captured.toString(), "-d", "-S", socket.toString(), "--json")
                        .redirectOutput(output.toFile())
                        .redirectError(error.toFile())
                        .start();
                assertTrue(process.waitFor(45, TimeUnit.SECONDS), "reload did not finish");
                assertEquals(0, process.exitValue(), Files.readString(error));
                var restored = server.session("restored").orElseThrow();
                var sessionOptions = restored.options().all();
                var windowOptions = restored.windows().getFirst().options().all();
                values.forEach((name, value) -> {
                    assertEquals(value, sessionOptions.get(name), name);
                    assertEquals(value, windowOptions.get(name), name);
                });
                assertEquals(keeper.id(), keeper.refresh().id());
                assertEquals(keeperWindow.id(), keeper.windows().getFirst().id());
            } finally {
                if (process != null && process.isAlive())
                    process.destroyForcibly().waitFor();
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void interruptionExitsWhileTheInstalledStdoutPipeIsUndrained() throws Exception {
        Path global = Files.createDirectory(directory.resolve(".tmuxp"));
        Files.writeString(global.resolve("large.yaml"), "session_name: large\ntext: " + "x".repeat(100_000) + "\n");
        var builder = command("ls", "--full", "--json");
        var argv = new ArrayList<>(List.of("python3", program("undrained_pipe").toString()));
        argv.addAll(builder.command());
        builder.command(argv).redirectError(ProcessBuilder.Redirect.INHERIT);
        Process probe = builder.start();
        try {
            assertTrue(probe.waitFor(45, TimeUnit.SECONDS), "undrained pipe probe did not finish");
            assertEquals(0, probe.exitValue());
        } finally {
            if (probe.isAlive()) probe.destroyForcibly().waitFor();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "true,false,0,false",
        "false,false,0,false",
        "true,true,0,false",
        "true,false,2,false",
        "true,false,2,true"
    })
    void progressUsesOnlyTerminalStderrAndHonorsDisable(
            boolean terminalError, boolean disabled, int panelLines, boolean cancelled) throws Exception {
        Path source = directory.resolve("progress.yaml");
        Path socket = directory.resolve("progress-socket");
        Path output = directory.resolve("stdout");
        Path error = directory.resolve("stderr");
        Path terminal = directory.resolve("terminal");
        Files.writeString(source, """
                session_name: progress
                before_script: /bin/sh -c 'printf "bootstrap-out\\n"; %sprintf "bootstrap-err\\n" >&2%s'
                windows:
                  - panes: [null, null]
                """.formatted(cancelled ? "sleep .08; " : "", cancelled ? "; sleep 30" : ""));
        var builder = command();
        builder.command(
                "python3",
                program("progress_panel").toString(),
                System.getProperty("workspace.cli.launcher"),
                source.toString(),
                socket.toString(),
                output.toString(),
                error.toString(),
                terminal.toString(),
                Boolean.toString(terminalError),
                Boolean.toString(disabled),
                Integer.toString(panelLines),
                Boolean.toString(cancelled));
        builder.environment().put("TERM", "xterm");
        builder.environment().put("NO_COLOR", "1");
        builder.environment().remove("TMUXP_PROGRESS");
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            Process probe = builder.start();
            try {
                assertTrue(probe.waitFor(45, TimeUnit.SECONDS));
                assertEquals(0, probe.exitValue());
                String stdout = Files.readString(output);
                String stderr = Files.readString(terminalError ? terminal : error);
                assertTrue(stdout.contains("bootstrap-out"), stdout);
                assertEquals(!cancelled, stdout.contains("Created session"), stdout);
                assertFalse(stdout.contains("PROGRESS_"), stdout);
                assertTrue(stderr.contains("bootstrap-err"), stderr);
                assertEquals(terminalError && !disabled, stderr.contains("PROGRESS_progress_1_2"), stderr);
                if (cancelled) {
                    assertTrue(stderr.contains("interrupted"), stderr);
                    assertTrue(stderr.lastIndexOf("\u001b[0J") > stderr.lastIndexOf("PROGRESS_"), stderr);
                }
            } finally {
                if (probe.isAlive()) probe.destroyForcibly().waitFor();
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void interruptionEmitsActualPartialEffectsAfterStreamingOutput() throws Exception {
        Path source = directory.resolve("workspace.yaml");
        Path socket = directory.resolve("socket");
        Files.writeString(source, """
                session_name: interrupted-java
                before_script: /bin/sh -c 'printf ready; sleep 30'
                windows:
                  - panes: [null]
                """);
        var process = command("load", source.toString(), "-S", socket.toString(), "-f", "/dev/null", "-d", "--ndjson")
                .redirectError(directory.resolve("error.log").toFile())
                .start();
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            try (var reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder output = new StringBuilder();
                boolean observed = false;
                for (String line; (line = reader.readLine()) != null; ) {
                    output.append(line).append('\n');
                    if (new ObjectMapper().readTree(line).path("event").asText().equals("script-output")) {
                        observed = true;
                        break;
                    }
                }
                assertTrue(observed && process.isAlive(), output.toString());
                assertEquals(
                        0,
                        new ProcessBuilder("kill", "-INT", Long.toString(process.pid()))
                                .start()
                                .waitFor());
                assertTrue(process.waitFor(4, TimeUnit.SECONDS), "CLI did not stop after SIGINT");
                output.append(reader.lines().collect(java.util.stream.Collectors.joining("\n")));
                assertEquals(130, process.exitValue());
                var last = new ObjectMapper()
                        .readTree(output.toString().lines().toList().getLast());
                assertEquals("failed", last.path("event").asText(), output.toString());
                assertEquals("partial", last.path("status").asText());
                assertTrue(last.path("errors")
                        .path(0)
                        .path("effects")
                        .path("owned_session")
                        .asBoolean());
                assertTrue(server.hasSession("interrupted-java"));
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /** A lookup ps could not run must not read as a session holding nothing; both exit non-zero. */
    @Test
    void anUnusableOwnedProcessLookupIsNotReadAsAnEmptySession() {
        assertThrows(java.io.IOException.class, () -> Children.hasCapturedDescendant("/bin/ps", -1));
    }

    @Test
    void exitedEditorCannotLeaveAnOwnedOutputPipeOpen() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(Path.of("/proc/self")) && Files.isExecutable(Path.of("/usr/bin/setsid")),
                "Reparented output ownership requires the Linux setsid session.");
        Path source = directory.resolve("workspace.yaml");
        Files.writeString(source, "session_name: editor\nwindows: []\n");
        var builder = command("edit", source.toString(), "--ndjson");
        builder.environment().put("EDITOR", "/bin/sh -c 'sleep 30 & printf \"%s\" $!' editor");
        builder.redirectError(directory.resolve("error.log").toFile());
        Process process = builder.start();
        long descendant = -1;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            assertTrue(line != null, "missing editor output");
            var first = new ObjectMapper().readTree(line);
            descendant = Long.parseLong(first.path("text").asText());
            assertTrue(process.waitFor(4, TimeUnit.SECONDS), "CLI is held open by an inherited pipe");
            var status = new ProcessBuilder("ps", "-o", "stat=", "-p", Long.toString(descendant)).start();
            String state = new String(status.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            status.waitFor();
            assertTrue(state.isEmpty() || state.startsWith("Z"), state);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
            if (descendant > 0) ProcessHandle.of(descendant).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void editorUsesItsControllingTerminalWhenJsonIsRedirected() throws Exception {
        Path source = directory.resolve("workspace.yaml");
        Path output = directory.resolve("result.json");
        Files.writeString(source, "session_name: editor\nwindows: []\n");
        Process process = new ProcessBuilder(
                        "python3",
                        program("editor_terminal").toString(),
                        System.getProperty("workspace.cli.launcher"),
                        source.toString(),
                        output.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        assertTrue(process.waitFor(45, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        var result = new ObjectMapper().readTree(Files.readString(output));
        assertEquals("", result.path("stdout").asText());
        assertEquals(0, result.path("child_status").asInt());
    }

    @Test
    void successfulEditorMayLeaveAServiceWithClosedOutputStreams() throws Exception {
        Path source = directory.resolve("workspace.yaml");
        Files.writeString(source, "session_name: editor\nwindows: []\n");
        var builder = command("edit", source.toString(), "--json");
        builder.environment().put("EDITOR", "/bin/sh -c 'sleep 30 >/dev/null 2>&1 & printf \"%s\" $!' editor");
        Process process = builder.start();
        long descendant = -1;
        try {
            assertTrue(process.waitFor(4, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            var result = new ObjectMapper().readTree(process.getInputStream());
            descendant = Long.parseLong(result.path("stdout").asText());
            var status = new ProcessBuilder("ps", "-o", "stat=", "-p", Long.toString(descendant)).start();
            String state = new String(status.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            status.waitFor();
            assertFalse(state.isEmpty() || state.startsWith("Z"), state);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
            if (descendant > 0) ProcessHandle.of(descendant).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    /**
     * The attach child gets this process's own descriptors, not a fresh open of the tty by path: a
     * redirected human load's attach child inherits that redirection too, exactly as a shell
     * redirection would, and fails to attach rather than drawing nothing on a client that connected.
     * A real terminal's client must actually render the session, not just connect: the earlier
     * reopen-by-path bug left a connected client with the screen blank.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void humanLoadAttachesThroughTheActualTerminalDevice(boolean redirected) throws Exception {
        Path source = directory.resolve("workspace.yaml");
        Path socket = directory.resolve("attach");
        Files.writeString(source, "session_name: attached\nwindows:\n  - panes: [null]\n");
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            try {
                Process process = new ProcessBuilder(
                                "python3",
                                program("attach_terminal").toString(),
                                System.getProperty("workspace.cli.launcher"),
                                source.toString(),
                                socket.toString(),
                                System.getProperty("libtmux.tmux", "tmux"),
                                directory.resolve("status.log").toString(),
                                Boolean.toString(redirected))
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                assertTrue(process.waitFor(45, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
                assertTrue(server.hasSession("attached"));
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void attachedLoadSwitchesOnlyTheInvokingClientWithRedirectedStreams() throws Exception {
        Path source = directory.resolve("switch.yaml");
        Path socket = directory.resolve("switch");
        Files.writeString(source, "session_name: switched\nwindows:\n  - panes: [null]\n");
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            server.newSession(spec ->
                    spec.named("origin").running("/bin/sh", "-i").env("ENV", "").env("PS1", "workspace-ready> "));
            server.newSession("other");
            try {
                Process child = new ProcessBuilder(
                                "python3",
                                program("switch_clients").toString(),
                                System.getProperty("workspace.cli.launcher"),
                                source.toString(),
                                socket.toString(),
                                System.getProperty("libtmux.tmux", "tmux"),
                                directory.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                assertTrue(child.waitFor(45, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /**
     * A key binding's {@code run-shell} sets {@code TMUX} but never {@code TMUX_PANE} and gives the
     * command no terminal. Inside tmux a switch needs neither: it must still switch the session's
     * most recently active client, with no {@code -c}, rather than demanding a terminal.
     */
    @Test
    void runShellStyleLoadSwitchesTheMostRecentClientWithoutATargetPane() throws Exception {
        Path source = directory.resolve("runshell.yaml");
        Path socket = directory.resolve("runshell-switch");
        Files.writeString(source, "session_name: from-runshell\nwindows:\n  - panes: [null]\n");
        Path diagnostics = directory.resolve("runshell-diagnostics.log");
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            server.newSession("keeper");
            try {
                Process process = new ProcessBuilder(
                                "python3",
                                program("run_shell_switch").toString(),
                                System.getProperty("workspace.cli.launcher"),
                                source.toString(),
                                socket.toString(),
                                System.getProperty("libtmux.tmux", "tmux"),
                                directory.resolve("runshell-output").toString())
                        .redirectError(diagnostics.toFile())
                        .start();
                boolean finished = process.waitFor(45, TimeUnit.SECONDS);
                String diagnosticText =
                        Files.exists(diagnostics) ? Files.readString(diagnostics) : "(no diagnostics file)";
                assertTrue(finished, diagnosticText);
                assertEquals(0, process.exitValue(), diagnosticText);
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /**
     * Inside tmux, a new session's load asks switch/detached/append. Answered "n" by keystroke in a
     * real pane (never piped), it must build the session detached and leave the client where it was.
     */
    @Test
    void interactiveNewSessionPromptAnsweredNoBuildsDetached() throws Exception {
        Path source = directory.resolve("prompt-new.yaml");
        Path socket = directory.resolve("prompt-new-socket");
        Files.writeString(source, "session_name: from-prompt\nwindows:\n  - panes: [null]\n");
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            server.newSession(spec ->
                    spec.named("keeper").running("/bin/sh", "-i").env("ENV", "").env("PS1", "workspace-ready> "));
            try {
                Process child = new ProcessBuilder(
                                "python3",
                                program("prompt_new_session").toString(),
                                System.getProperty("workspace.cli.launcher"),
                                source.toString(),
                                socket.toString(),
                                System.getProperty("libtmux.tmux", "tmux"),
                                directory.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                assertTrue(child.waitFor(45, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /**
     * A session that already exists asks once whether to attach; answered "n" it changes nothing
     * and never asks the new-session question too.
     */
    @Test
    void interactiveExistingSessionPromptAnsweredNoChangesNothing() throws Exception {
        Path source = directory.resolve("prompt-exists.yaml");
        Path socket = directory.resolve("prompt-exists-socket");
        Files.writeString(
                source, "session_name: home\nwindows:\n  - panes: [null]\n  - window_name: extra\n    panes: [null]\n");
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            server.newSession(spec ->
                    spec.named("keeper").running("/bin/sh", "-i").env("ENV", "").env("PS1", "workspace-ready> "));
            server.newSession("home");
            try {
                Process child = new ProcessBuilder(
                                "python3",
                                program("prompt_existing_session").toString(),
                                System.getProperty("workspace.cli.launcher"),
                                source.toString(),
                                socket.toString(),
                                System.getProperty("libtmux.tmux", "tmux"),
                                directory.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                assertTrue(child.waitFor(45, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /**
     * The existing-session question is asked once, about the last input only. Declining it must
     * leave that one input untouched without aborting the load: every input before it still builds
     * and counts toward the result, and the tool says in the open what it did not do.
     */
    @Test
    void interactiveExistingSessionPromptOnlyScopesTheDeclinedInput() throws Exception {
        Path first = directory.resolve("prompt-scope-first.yaml");
        Path second = directory.resolve("prompt-scope-second.yaml");
        Path socket = directory.resolve("prompt-scope-socket");
        Files.writeString(first, "session_name: fresh\nwindows:\n  - panes: [null]\n");
        Files.writeString(
                second,
                "session_name: standing\nwindows:\n  - panes: [null]\n  - window_name: extra\n    panes: [null]\n");
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            server.newSession(spec ->
                    spec.named("keeper").running("/bin/sh", "-i").env("ENV", "").env("PS1", "workspace-ready> "));
            server.newSession("standing");
            try {
                Process child = new ProcessBuilder(
                                "python3",
                                program("prompt_scope").toString(),
                                System.getProperty("workspace.cli.launcher"),
                                first.toString(),
                                second.toString(),
                                socket.toString(),
                                System.getProperty("libtmux.tmux", "tmux"),
                                directory.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                assertTrue(child.waitFor(45, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    /**
     * Declining a prompt is an answer, not a failure. Only a real terminal can be asked, so this is
     * the only place the answer can be given: every other test reaches the same code with --yes or
     * --json, which skip the question entirely.
     */
    @Test
    void decliningTheSavePromptWritesNothingAndSucceeds() throws Exception {
        Path source = directory.resolve("declined.yaml");
        Path target = directory.resolve("declined.json");
        Path status = directory.resolve("declined-status");
        Path transcript = directory.resolve("declined-transcript");
        Files.writeString(source, "session_name: declined\nwindows: []\n");
        Process probe = new ProcessBuilder(
                        "python3",
                        program("decline_save").toString(),
                        System.getProperty("workspace.cli.launcher"),
                        source.toString(),
                        target.toString(),
                        status.toString(),
                        transcript.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            assertTrue(probe.waitFor(45, TimeUnit.SECONDS));
            assertEquals(0, probe.exitValue());
            String shown = Files.readString(transcript);
            assertEquals("0", Files.readString(status).strip(), shown);
            assertFalse(Files.exists(target), shown);
            assertTrue(shown.contains("Not saved"), shown);
        } finally {
            if (probe.isAlive()) probe.destroyForcibly().waitFor();
        }
    }

    @Test
    void aFullLogCannotReplaceTheOriginalLoadResult() throws Exception {
        Path source = directory.resolve("failed.yaml");
        Path socket = directory.resolve("failed-log");
        Files.writeString(
                source,
                "session_name: partial-log\nwindows:\n  - options:\n      not-a-tmux-option: invalid\n    panes: [null]\n");
        var builder = command(
                "load",
                source.toString(),
                "-d",
                "-S",
                socket.toString(),
                "-f",
                "/dev/null",
                "--ndjson",
                "--log-file",
                directory.resolve("full.jsonl").toString());
        var arguments = new ArrayList<>(List.of("python3", program("full_log").toString()));
        arguments.addAll(builder.command());
        builder.command(arguments);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().put("JAVA_OPTS", "-XX:-UsePerfData -XX:ActiveProcessorCount=2");
        Process process = builder.start();
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            try {
                assertTrue(process.waitFor(45, TimeUnit.SECONDS));
                assertEquals(1, process.exitValue());
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                var terminal =
                        new ObjectMapper().readTree(output.lines().toList().getLast());
                assertEquals("failed", terminal.path("event").asText(), output + error);
                assertEquals("error", terminal.path("status").asText());
                var effects = terminal.path("errors").path(0).path("effects");
                assertEquals("windows", effects.path("stage").asText());
                assertEquals(1, effects.path("window_ids").size());
                assertEquals(1, effects.path("pane_ids").size());
                assertTrue(effects.path("session_removed").asBoolean(), output);
                assertTrue(error.contains("not-a-tmux-option"), error);
                assertTrue(error.contains("\"code\":\"log_unavailable\""), error);
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
                if (server.isAlive()) server.killServer();
            }
        }
    }
}
