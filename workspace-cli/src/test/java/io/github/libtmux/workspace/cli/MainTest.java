package io.github.libtmux.workspace.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MainTest {
    @TempDir
    Path directory;

    private record Result(int code, String out, String err) {}

    private Result invoke(String... arguments) {
        return invoke(Map.of(), arguments);
    }

    private Result invoke(Map<String, String> overrides, String... arguments) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var environment = new java.util.HashMap<>(Map.of("HOME", directory.toString(), "PATH", ""));
        environment.putAll(overrides);
        int code = Main.run(arguments, environment, directory, InputStream.nullInputStream(), out, err);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpAndEveryLeafOutputPlacementAreBackendFree() {
        for (List<String> leaf : List.of(
                List.of("load"),
                List.of("freeze"),
                List.of("convert"),
                List.of("ls"),
                List.of("search"),
                List.of("debug-info"),
                List.of("edit"),
                List.of("shell"),
                List.of("import", "teamocil"),
                List.of("import", "tmuxinator"))) {
            for (String mode : List.of("--json", "--ndjson")) {
                for (boolean prefix : List.of(true, false)) {
                    var args = new ArrayList<String>();
                    if (prefix) args.add(mode);
                    args.addAll(leaf);
                    if (!prefix) args.add(mode);
                    args.add("--help");
                    Result result = invoke(args.toArray(String[]::new));
                    assertEquals(0, result.code(), result.err());
                    assertTrue(result.out().contains("--ndjson"), result.out());
                    assertEquals("", result.err());
                }
            }
        }
        assertEquals(0, invoke("--version").code());
    }

    @Test
    void malformedArgumentsLeaveMachineStdoutEmpty() throws Exception {
        for (List<String> args : List.of(
                List.of("load"),
                List.of("import", "teamocil"),
                List.of("import", "tmuxinator"),
                List.of("load", "x", "--unknown"),
                List.of("load", "x", "-2", "-8"),
                List.of("load", "x", "--progress-lines", "bad"),
                List.of("load", "x", "-d", "--progress-lines=-3"),
                List.of("shell", "--ipython", "--code"),
                List.of("freeze", "-f", "toml"),
                List.of("--color", "purple", "ls"),
                List.of("search"),
                List.of("search", "["))) {
            var machine = new ArrayList<>(List.of("--json"));
            machine.addAll(args);
            Result result = invoke(machine.toArray(String[]::new));
            assertEquals(2, result.code(), result.toString());
            assertEquals("", result.out());
            assertEquals(
                    "usage",
                    new ObjectMapper().readTree(result.err()).path("code").asText());
        }
    }

    @Test
    void legacyColorModeIsRejectedBeforeWorkspaceOrBackendLookup() throws Exception {
        Result result = invoke("load", "missing.yaml", "-d", "-8", "--json");
        assertEquals(2, result.code(), result.toString());
        assertEquals("", result.out());
        var diagnostic = new ObjectMapper().readTree(result.err());
        assertEquals("unsupported_color_mode", diagnostic.path("code").asText());
        assertTrue(diagnostic.path("message").asText().contains("tmux 3.2a"));
    }

    @Test
    void scriptedExtensionAppendIsRejectedAcrossAllInputsBeforeRuntimeLookup() throws Exception {
        Path first = directory.resolve("first.yaml");
        Path second = directory.resolve("second.yaml");
        Files.writeString(first, "session_name: first\nwindows: [{}]\n");
        Files.writeString(second, """
                session_name: second
                plugins: [example.Plugin]
                before_script: /bin/false
                windows: [{}]
                """);
        Result result =
                invoke(Map.of("TMUX_PANE", "%0"), "load", first.toString(), second.toString(), "--append", "--json");
        assertEquals(2, result.code(), result.toString());
        assertEquals("", result.out());
        assertEquals(
                "unsupported_combination",
                new ObjectMapper().readTree(result.err()).path("code").asText());
    }

    @Test
    void extensionRuntimeIsCheckedBeforeTheFirstNativeInputCanLoad() throws Exception {
        Path first = directory.resolve("first.yaml");
        Path second = directory.resolve("second.yaml");
        Path python = directory.resolve("python");
        Path marker = directory.resolve("version-checked");
        Files.writeString(first, "session_name: first\nwindows: [{}]\n");
        Files.writeString(second, "session_name: second\nplugins: [example.Plugin]\nwindows: [{}]\n");
        Files.writeString(python, "#!/bin/sh\nprintf checked > '" + marker + "'\nprintf '0.0\\n'\n");
        assertTrue(python.toFile().setExecutable(true));
        Result result = invoke(
                Map.of("TMUX_WORKSPACE_PYTHON", python.toString()),
                "load",
                first.toString(),
                second.toString(),
                "-d",
                "--json");
        assertEquals(1, result.code(), result.toString());
        assertEquals("", result.out());
        assertTrue(Files.exists(marker));
        assertEquals(
                "python_runtime",
                new ObjectMapper().readTree(result.err()).path("code").asText());
    }

    @Test
    void emptyDiscoveryUsesStableRecordsAndNeverExecutesTmux() throws Exception {
        Result json = invoke("ls", "--json");
        assertEquals(0, json.code(), json.err());
        var value = new ObjectMapper().readTree(json.out());
        assertTrue(value.path("workspaces").isArray());
        assertEquals(0, value.path("workspaces").size());
        assertFalse(value.path("global_workspace_dirs").isEmpty());
        assertEquals("", invoke("ls", "--ndjson").out());
        assertEquals("[]\n", invoke("search", "missing", "--json").out());
    }

    @Test
    void bashGenerationUsesTheSelectedMachineArtifact() throws Exception {
        String script = picocli.AutoComplete.bash("tmux-workspace", Arguments.create());
        Result plain = invoke("--generate", "bash");
        assertEquals(0, plain.code(), plain.err());
        assertEquals(script, plain.out());
        assertEquals("", plain.err());
        for (var arguments : List.of(
                List.of("--generate", "bash", "--json"),
                List.of("--json", "--generate", "bash"),
                List.of("--generate", "bash", "--ndjson"),
                List.of("--ndjson", "--generate", "bash"),
                List.of("--json", "--generate", "bash", "--ndjson"),
                List.of("--ndjson", "--generate", "bash", "--json"))) {
            Result result = invoke(arguments.toArray(String[]::new));
            assertEquals(0, result.code(), result.err());
            assertTrue(result.out().startsWith("{"), arguments.toString());
            assertEquals(1, result.out().lines().count());
            var artifact = new ObjectMapper().readTree(result.out());
            assertEquals(1, artifact.path("schema_version").asInt());
            assertEquals("generate", artifact.path("command").asText());
            assertEquals("bash", artifact.path("format").asText());
            assertEquals(script, artifact.path("script").asText());
            assertEquals("ok", artifact.path("status").asText());
            if (arguments.contains("--ndjson")) {
                assertEquals("completed", artifact.path("event").asText());
                assertEquals(1, artifact.path("sequence").asInt());
            } else {
                assertFalse(artifact.has("event"));
                assertFalse(artifact.has("sequence"));
            }
            assertEquals("", result.err());
        }
    }

    @Test
    void schemaGenerationRetainsItsMetadataDocument() {
        Result plain = invoke("--generate", "schema");
        assertEquals(0, plain.code(), plain.err());
        for (String mode : List.of("--json", "--ndjson")) {
            Result result = invoke("--generate", "schema", mode);
            assertEquals(0, result.code(), result.err());
            assertEquals(plain.out(), result.out());
            assertEquals("", result.err());
        }
    }

    @Test
    void generationReportsClosedOutputThroughTheExistingDiagnostic() throws Exception {
        for (var arguments : List.of(
                List.of("--generate", "bash"),
                List.of("--generate", "bash", "--json"),
                List.of("--generate", "bash", "--ndjson"))) {
            var closed = OutputStream.nullOutputStream();
            closed.close();
            var error = new ByteArrayOutputStream();
            int status = Main.run(
                    arguments.toArray(String[]::new),
                    Map.of("HOME", directory.toString(), "PATH", ""),
                    directory,
                    InputStream.nullInputStream(),
                    closed,
                    error);
            assertEquals(1, status, arguments.toString());
            String diagnostic = error.toString(StandardCharsets.UTF_8);
            if (arguments.size() > 2) {
                var value = new ObjectMapper().readTree(diagnostic);
                assertEquals("invalid_config", value.path("code").asText());
                assertTrue(value.path("message").asText().contains("closed"));
            } else assertTrue(diagnostic.contains("closed"));
        }
    }

    @Test
    void interruptedOutputReturnsWithoutClosingTheBorrowedSink() throws Exception {
        Path global = Files.createDirectory(directory.resolve(".tmuxp"));
        Files.writeString(global.resolve("one.yaml"), "session_name: one\nwindows: []\n");
        for (var arguments : List.of(
                List.of("ls"),
                List.of("ls", "--json"),
                List.of("ls", "--ndjson"),
                List.of("--help"),
                List.of("--generate", "bash"),
                List.of("--generate", "bash", "--json"),
                List.of("--generate", "bash", "--ndjson"),
                List.of("missing-command", "--json"))) {
            var sink = new BlockedOutput();
            var status = new AtomicInteger(-1);
            var interrupted = new AtomicBoolean();
            boolean diagnostic = arguments.getFirst().equals("missing-command");
            Thread owner = Thread.ofPlatform().unstarted(() -> {
                status.set(Main.run(
                        arguments.toArray(String[]::new),
                        Map.of("HOME", directory.toString(), "PATH", ""),
                        directory,
                        InputStream.nullInputStream(),
                        diagnostic ? OutputStream.nullOutputStream() : sink,
                        diagnostic ? sink : OutputStream.nullOutputStream()));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            try {
                owner.start();
                assertTrue(sink.entered.await(2, TimeUnit.SECONDS), arguments.toString());
                owner.interrupt();
                owner.join(1_000);
                assertFalse(owner.isAlive(), arguments.toString());
                assertEquals(130, status.get(), arguments.toString());
                assertTrue(interrupted.get(), arguments.toString());
                assertTrue(sink.virtual.get(), arguments.toString());
                assertFalse(sink.closed.get(), arguments.toString());
            } finally {
                sink.release.countDown();
                owner.join(2_000);
                assertTrue(sink.finished.await(2, TimeUnit.SECONDS));
            }
        }
    }

    static final class BlockedOutput extends OutputStream {
        private final String marker;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicBoolean virtual = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();

        BlockedOutput() {
            this("");
        }

        BlockedOutput(String marker) {
            this.marker = marker;
        }

        @Override
        public void write(int value) {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (entered.getCount() != 0 && !new String(bytes, offset, length, StandardCharsets.UTF_8).contains(marker))
                return;
            virtual.set(Thread.currentThread().isVirtual());
            entered.countDown();
            boolean ready = false;
            while (!ready) {
                try {
                    release.await();
                    ready = true;
                } catch (InterruptedException ignored) {
                    // The caller, not cancellation, releases this borrowed sink.
                }
            }
            finished.countDown();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @Test
    void alreadyInterruptedInvocationDoesNotOpenItsLog() throws Exception {
        Path log = directory.resolve("interrupted.jsonl");
        var result = new java.util.concurrent.atomic.AtomicReference<Result>();
        Thread owner = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            result.set(invoke("load", "missing.yaml", "-d", "--json", "--log-file", log.toString()));
        });
        owner.join(2_000);
        assertFalse(owner.isAlive());
        assertEquals(130, result.get().code(), result.get().toString());
        assertFalse(Files.exists(log));
        assertEquals("", result.get().out());
        assertEquals(
                "interrupted",
                new ObjectMapper().readTree(result.get().err()).path("code").asText());
    }

    @Test
    void cancelledChildWritersCannotConsumeTheFinalResultAllowance() throws Exception {
        var sink = new BlockedOutput();
        var result = new ByteArrayOutputStream();
        var firstCancelled = new AtomicBoolean();
        var secondCancelled = new AtomicBoolean();
        try (var streams = new BorrowedOutput(result, sink)) {
            Thread first = Thread.ofPlatform().unstarted(() -> {
                try {
                    streams.error().write('a');
                } catch (java.io.IOException failure) {
                    firstCancelled.set(failure instanceof java.io.InterruptedIOException);
                }
            });
            Thread second = Thread.ofPlatform().unstarted(() -> {
                Thread.currentThread().interrupt();
                try {
                    streams.error().write('b');
                } catch (java.io.IOException failure) {
                    secondCancelled.set(failure instanceof java.io.InterruptedIOException);
                }
            });
            try {
                first.start();
                assertTrue(sink.entered.await(2, TimeUnit.SECONDS));
                first.interrupt();
                first.join(1_000);
                assertFalse(first.isAlive());
                second.start();
                second.join(1_000);
                assertFalse(second.isAlive());
                assertTrue(firstCancelled.get());
                assertTrue(secondCancelled.get());
                streams.output().write("partial\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("partial\n", result.toString(StandardCharsets.UTF_8));
            } finally {
                sink.release.countDown();
                first.join(1_000);
                if (second.getState() != Thread.State.NEW) second.join(1_000);
                assertTrue(sink.finished.await(2, TimeUnit.SECONDS));
            }
        }
        assertFalse(sink.closed.get());
    }

    @Test
    void loggingAppendsPreflightErrorsWithoutTouchingMachineStdout() throws Exception {
        Path log = directory.resolve("operations.jsonl");
        Result failed = invoke("load", "missing.yaml", "-d", "--json", "--log-file", log.toString());
        assertEquals(1, failed.code());
        assertEquals("", failed.out());
        assertTrue(Files.isRegularFile(log), failed.toString());
        var records = Files.readAllLines(log);
        assertEquals(1, records.size());
        var record = new ObjectMapper().readTree(records.getFirst());
        assertEquals("error", record.path("level").asText());
        assertEquals("command-failed", record.path("event").asText());
        assertEquals(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(log));
        failed = invoke("load", "missing.yaml", "-d", "--json", "--log-file", log.toString(), "--log-level", "debug");
        assertEquals(1, failed.code());
        assertEquals(3, Files.readAllLines(log).size());
        assertEquals("", failed.out());
        for (Path invalidPath : List.of(directory, directory.resolve("missing/log.jsonl"))) {
            Result invalid = invoke("load", "missing.yaml", "-d", "--json", "--log-file", invalidPath.toString());
            assertEquals(1, invalid.code());
            assertEquals("", invalid.out());
            assertEquals(
                    "log_file",
                    new ObjectMapper().readTree(invalid.err()).path("code").asText());
        }
    }

    @Test
    void treeGroupsWorkspacesByDirectoryWithoutChangingMachineRecords() throws Exception {
        Path global = Files.createDirectory(directory.resolve(".tmuxp"));
        Files.writeString(global.resolve("first.yaml"), "session_name: one\nwindows: []\n");
        Files.writeString(global.resolve("second.yaml"), "session_name: two\nwindows: []\n");
        Result tree = invoke("ls", "--tree");
        assertEquals(0, tree.code(), tree.err());
        assertEquals(
                1, tree.out().lines().filter(line -> line.contains("~/.tmuxp")).count(), tree.out());
        assertTrue(tree.out().contains("|-- first"), tree.out());
        assertTrue(tree.out().contains("|-- second"), tree.out());
        assertEquals(
                invoke("ls", "--json").out(), invoke("ls", "--tree", "--json").out());
    }

    @Test
    void yamlAliasesAndMergesPreserveNestedValuesAndTextDates() throws Exception {
        Path source = directory.resolve("aliases.yaml");
        Files.writeString(source, """
                session_name: aliases
                windows:
                  - &window
                    window_name: first
                    shell_command_before: &setup [echo ready]
                    panes: [null]
                  - <<: *window
                    window_name: second
                    shell_command_before: *setup
                date: 2026-09-09
                """);
        Result result = invoke("convert", source.toString(), "--json");
        assertEquals(0, result.code(), result.toString());
        var document = new ObjectMapper().readTree(result.out());
        assertTrue(document.path("windows").path(1).path("panes").isArray(), result.out());
        assertEquals(
                document.path("windows").path(0).path("shell_command_before"),
                document.path("windows").path(1).path("shell_command_before"));
        assertEquals(
                "first", document.path("windows").path(0).path("window_name").asText());
        assertEquals(
                "second", document.path("windows").path(1).path("window_name").asText());
        assertEquals("2026-09-09", document.path("date").asText());
        assertFalse(document.path("windows").path(1).has("<<"));
    }

    @Test
    void documentParsingRejectsNonJsonAndUnrepresentableYaml() throws Exception {
        for (var fixture : Map.of(
                        "yaml-in-json.json", "session_name: invalid\n",
                        "duplicate.json", "{\"name\":1,\"name\":2}",
                        "duplicate.yaml", "name: one\nname: two\n",
                        "multiple.yaml", "name: one\n---\nname: two\n",
                        "multiple.json", "{} {}",
                        "cycle.yaml", "cycle: &cycle [*cycle]\n",
                        "typed.yaml", "value: !!java.net.URL ['https://invalid.example']\n",
                        "keys.yaml", "true: value\n",
                        "nonfinite.yaml", "value: .nan\n")
                .entrySet()) {
            Path source = directory.resolve(fixture.getKey());
            Files.writeString(source, fixture.getValue());
            Result result = invoke("convert", source.toString(), "--json");
            assertEquals(1, result.code(), fixture.getKey() + ": " + result);
            assertEquals("", result.out());
            assertEquals(
                    "invalid_config",
                    new ObjectMapper().readTree(result.err()).path("code").asText());
        }
    }

    @Test
    void aliasExpansionHasABoundedJsonRepresentation() throws Exception {
        Path source = directory.resolve("expansion.yaml");
        StringBuilder yaml = new StringBuilder("a0: &a0 [text, text, text]\n");
        for (int index = 1; index <= 10; index++)
            yaml.append("a")
                    .append(index)
                    .append(": &a")
                    .append(index)
                    .append(" [*a")
                    .append(index - 1)
                    .append(", *a")
                    .append(index - 1)
                    .append(", *a")
                    .append(index - 1)
                    .append("]\n");
        Files.writeString(source, yaml);
        Result result = invoke("convert", source.toString(), "--json");
        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(new ObjectMapper()
                .readTree(result.err())
                .path("message")
                .asText()
                .contains("100000 values"));
    }

    @Test
    void conversionKeepsExtensionFieldsAndProtectsExistingDestinations() throws Exception {
        Path source = directory.resolve("source.yaml");
        Files.writeString(source, "session_name: demo\nwindows: []\ncustom:\n  values: [true, null, 雪]\n");
        Result converted = invoke("convert", source.toString(), "--json");
        assertEquals(0, converted.code(), converted.err());
        var document = new ObjectMapper().readTree(converted.out());
        assertEquals("雪", document.path("custom").path("values").path(2).asText());
        Path target = directory.resolve("existing.json");
        Files.writeString(target, "sentinel");
        Result rejected = invoke("convert", source.toString(), "--json", "--save-to", target.toString());
        assertEquals(1, rejected.code());
        assertEquals("sentinel", Files.readString(target));
        assertEquals(
                0,
                invoke("convert", source.toString(), "--json", "--save-to", target.toString(), "--force")
                        .code());
        assertEquals(document, new ObjectMapper().readTree(Files.readString(target)));
    }

    @Test
    void tmuxinatorImportKeepsCommandGroupsAndInvocationDirectories() throws Exception {
        Path source = Files.createDirectories(directory.resolve("inputs")).resolve("project.yaml");
        Files.writeString(source, """
                name: imported
                root: not-created
                pre_window: ['false', 'printf project']
                windows:
                  - work:
                      root: child
                      pre: ['printf first', 'printf second']
                      panes:
                        - ['printf one', 'printf two']
                        - null
                  - sequence: ['printf three', 'printf four']
                """);
        Result imported = invoke("import", "tmuxinator", source.toString(), "--json");
        assertEquals(0, imported.code(), imported.toString());
        var value = new ObjectMapper().readTree(imported.out());
        assertEquals(
                directory.resolve("not-created").toString(),
                value.path("start_directory").asText());
        assertEquals("false; printf project", value.path("shell_command_before").asText());
        var first = value.path("windows").path(0);
        assertEquals(
                directory.resolve("not-created/child").toString(),
                first.path("start_directory").asText());
        assertEquals(
                "printf first && printf second",
                first.path("shell_command_before").asText());
        assertEquals(2, first.path("panes").size());
        assertEquals(2, first.path("panes").path(0).path("shell_command").size());
        assertEquals(1, value.path("windows").path(1).path("panes").size());
        assertEquals(
                2,
                value.path("windows")
                        .path(1)
                        .path("panes")
                        .path(0)
                        .path("shell_command")
                        .size());
        assertTrue(first.path("focus").asBoolean());
        assertTrue(first.path("panes").path(0).path("focus").asBoolean());
        assertFalse(Files.exists(directory.resolve("not-created")));
        Path saved = Files.createDirectories(directory.resolve("elsewhere")).resolve("project.json");
        Result save = invoke(
                "import",
                "tmuxinator",
                source.toString(),
                "--json",
                "--workspace-format",
                "json",
                "--save-to",
                saved.toString());
        assertEquals(0, save.code(), save.toString());
        assertEquals(value, new ObjectMapper().readTree(Files.readString(saved)));
        Result load = invoke("load", saved.toString(), "-d", "--json");
        assertEquals(1, load.code());
        assertTrue(load.err().contains("start_directory is not a directory"), load.toString());
    }

    @Test
    void teamocilImportPreservesCommandsOptionsAndFirstFocus() throws Exception {
        Path source = directory.resolve("team.yaml");
        Files.writeString(source, """
                session:
                  name: imported
                  windows:
                    - name: first
                      options: {automatic-rename: false, '@imported': value}
                      panes:
                        - commands: ['false', 'printf one']
                        - commands: ['printf two']
                          focus: true
                        - cmd: echo <%= literal %>
                          focus: true
                    - name: second
                      focus: true
                      splits: [{cmd: printf four}, {cmd: printf five}]
                    - name: third
                      focus: true
                      panes: []
                """);
        Result imported = invoke("import", "teamocil", source.toString(), "--json");
        assertEquals(0, imported.code(), imported.toString());
        var value = new ObjectMapper().readTree(imported.out());
        var windows = value.path("windows");
        assertEquals(
                "false; printf one",
                windows.path(0).path("panes").path(0).path("shell_command").asText());
        assertEquals("value", windows.path(0).path("options").path("@imported").asText());
        assertTrue(windows.path(0).path("panes").path(1).path("focus").asBoolean());
        assertFalse(windows.path(0).path("panes").path(2).path("focus").asBoolean());
        // Teamocil evaluates no templates, so this text is ordinary and survives.
        assertEquals(
                "echo <%= literal %>",
                windows.path(0).path("panes").path(2).path("shell_command").asText());
        assertTrue(windows.path(1).path("focus").asBoolean());
        assertFalse(windows.path(2).path("focus").asBoolean());
        assertTrue(windows.path(1).path("panes").path(0).path("focus").asBoolean());
        assertEquals(1, windows.path(2).path("panes").size());
        Path saved = directory.resolve("native.json");
        Files.writeString(saved, imported.out());
        Main.Context context = new Main.Context(
                Map.of("HOME", directory.toString()),
                directory,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
        WorkspacePlan plan = WorkspacePlan.read(context, saved, "");
        assertEquals(3, plan.windows().getFirst().panes().size());
        assertEquals("off", plan.windows().getFirst().options().get("automatic-rename"));
    }

    @Test
    void importsRejectUnsupportedBehaviorAndMalformedValuesBeforeOutputOrSave() throws Exception {
        Path source = directory.resolve("unsupported.yaml");
        Path saved = directory.resolve("retained.json");
        List<String> tmuxinator = List.of(
                "name: demo\npre: printf host\nwindows: [{one: null}]\n",
                "name: demo\npost: printf host\nwindows: [{one: null}]\n",
                "name: demo\non_project_start: printf hook\nwindows: [{one: null}]\n",
                "name: demo\nsocket_name: other\nwindows: [{one: null}]\n",
                "name: demo\nwindows: [{one: {panes: [{title: printf pane}]}}]\n",
                "name: demo\nwindows: [{one: {pre: printf dropped, panes: []}}]\n",
                "name: demo\nwindows: [{one: {synchronize: before, panes: [one, two]}}]\n",
                "name: demo\nwindows: [{one: {layout: invalid-layout}}]\n",
                "name: demo\nwindows: [{one: [17]}]\n",
                "name: demo\nproject_name: other\nwindows: [{one: null}]\n",
                "name: demo\nwindows: []\n",
                "name: '<%= name %>'\nwindows: [{one: null}]\n",
                "name: demo\nwindows: [{one: ['echo <%= dynamic_command %>']}]\n",
                "name: demo\nwindows: [{'<%= dynamic_window %>': null}]\n");
        List<String> teamocil = List.of(
                "name: demo\nwindows: [{name: one, clear: true, panes: [one]}]\n",
                "name: demo\nwindows: [{name: one, filters: {before: echo}, panes: [one]}]\n",
                "name: demo\nwindows: [{name: one, panes: [{commands: [17]}]}]\n",
                "name: demo\nwindows: [{name: one, options: {synchronize-panes: true}, panes: [one, two]}]\n",
                "name: demo\nwindows: [{name: one, focus: yes, panes: [one]}]\n".replace("yes", "'yes'"),
                "name: demo\nwindows: [{name: one, panes: [one], splits: [two]}]\n");
        for (String kind : List.of("tmuxinator", "teamocil")) {
            for (String yaml : kind.equals("tmuxinator") ? tmuxinator : teamocil) {
                Files.writeString(source, yaml);
                Files.writeString(saved, "retained");
                Result result =
                        invoke("import", kind, source.toString(), "--json", "--save-to", saved.toString(), "--force");
                assertEquals(1, result.code(), yaml + result);
                assertEquals("", result.out(), yaml);
                assertEquals("retained", Files.readString(saved), yaml);
                if (yaml.contains("<%")) assertTrue(result.err().contains("ERB"), yaml + result);
                Result stream = invoke("import", kind, source.toString(), "--ndjson");
                assertEquals(1, stream.code(), yaml + stream);
                assertFalse(stream.out().contains("completed"), yaml + stream);
                assertEquals(0, invoke("convert", source.toString(), "--json").code(), yaml);
            }
        }
    }

    @Test
    void importedCommandsDoNotBecomeNativeBlankPaneShorthand() throws Exception {
        Path source = directory.resolve("literal.yaml");
        Main.Context context = new Main.Context(
                Map.of("HOME", directory.toString()),
                directory,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
        for (String kind : List.of("tmuxinator", "teamocil")) {
            for (String command : List.of("blank", "empty", "pane")) {
                Files.writeString(
                        source,
                        "name: literal\nwindows: ["
                                + (kind.equals("tmuxinator")
                                        ? "{one: ['" + command + "']}"
                                        : "{name: one, panes: [{commands: ['" + command + "']}]}")
                                + "]\n");
                Result imported = invoke("import", kind, source.toString(), "--json");
                assertEquals(0, imported.code(), imported.toString());
                Path saved = directory.resolve("literal.json");
                Files.writeString(saved, imported.out());
                var commands = WorkspacePlan.read(context, saved, "")
                        .windows()
                        .getFirst()
                        .panes()
                        .getFirst()
                        .commands();
                assertEquals(1, commands.size(), kind + " " + command);
                assertEquals(" " + command, commands.getFirst().text());
            }
        }
    }

    @Test
    void importAliasesUseNullFallbackAndAfterSynchronization() throws Exception {
        Path source = directory.resolve("aliases.yaml");
        Files.writeString(source, """
                project_name: null
                name: aliases
                project_root: null
                root: .
                tabs: null
                windows:
                  - work:
                      synchronize: after
                      panes: [null, ['printf a', 'printf b']]
                """);
        Result imported = invoke("import", "tmuxinator", source.toString(), "--json");
        assertEquals(0, imported.code(), imported.toString());
        var value = new ObjectMapper().readTree(imported.out());
        assertEquals("aliases", value.path("session_name").asText());
        assertTrue(value.path("windows")
                .path(0)
                .path("options_after")
                .path("synchronize-panes")
                .asBoolean());
        Files.writeString(source, "windows: [{name: default, panes: [null]}]\n");
        imported = invoke("import", "teamocil", source.toString(), "--json");
        assertEquals(0, imported.code(), imported.toString());
        value = new ObjectMapper().readTree(imported.out());
        assertEquals("aliases", value.path("session_name").asText());
        assertEquals(directory.toString(), value.path("start_directory").asText());
    }

    @Test
    void editorCapturesControlBytesAndReturnsItsExitStatus() throws Exception {
        Path source = directory.resolve("workspace.yaml");
        Files.writeString(source, "session_name: edited\nwindows: []\n");
        Result result = invoke(
                Map.of("EDITOR", "/bin/sh -c 'printf \"one\\ntwo\\033\"; exit 7' editor"),
                "edit",
                source.toString(),
                "--json");
        assertEquals(7, result.code(), result.toString());
        var captured = new ObjectMapper().readTree(result.out());
        assertEquals("one\ntwo\u001b", captured.path("stdout").asText());
        assertEquals(7, captured.path("child_status").asInt());
        assertFalse(result.out().contains("\u001b"));
        assertEquals(
                "child_failed",
                new ObjectMapper().readTree(result.err()).path("code").asText());
    }

    @Test
    void missingPythonBridgeReportsItsRuntimeRequirement() throws Exception {
        Result result = invoke(Map.of("TMUX_WORKSPACE_PYTHON", "/missing/python"), "shell", "-c", "print(1)", "--json");
        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertEquals(
                "python_runtime",
                new ObjectMapper().readTree(result.err()).path("code").asText());
    }

    @Test
    void readinessPoliciesValidateBeforeTheBackendIsResolved() throws Exception {
        Path source = directory.resolve("readiness.yaml");
        Main.Context context = new Main.Context(
                Map.of("HOME", directory.toString()),
                directory,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
        for (String policy :
                List.of("auto", "always", "never", "true", "false", "1", "0", "' YES '", "'off'", "null")) {
            Files.writeString(
                    source,
                    "session_name: readiness\nworkspace_builder_options:\n  pane_readiness: " + policy
                            + "\nwindows:\n  - panes: [null]\n");
            WorkspacePlan.read(context, source, "");
        }
        for (String catalog :
                List.of("[]", "false", "{pane_readiness: sometimes}", "{pane_readiness: []}", "{unknown: true}")) {
            Files.writeString(
                    source,
                    "session_name: readiness\nworkspace_builder_options: " + catalog
                            + "\nwindows:\n  - panes: [null]\n");
            Result result = invoke("load", source.toString(), "-d", "--json");
            assertEquals(1, result.code());
            assertEquals("", result.out());
            assertEquals(
                    "invalid_config",
                    new ObjectMapper().readTree(result.err()).path("code").asText());
            assertTrue(result.err().contains("workspace_builder_options"), result.err());
        }
    }

    @Test
    void paneShellAliasWorksWithoutAmbiguousLaunchCommands() throws Exception {
        Path source = directory.resolve("shell.yaml");
        Main.Context context = new Main.Context(
                Map.of("HOME", directory.toString()),
                directory,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
        for (String key : List.of("shell", "pane_shell")) {
            Files.writeString(source, "session_name: shell\nwindows:\n  - panes:\n      - " + key + ": /bin/cat\n");
            assertEquals(
                    "/bin/cat",
                    WorkspacePlan.read(context, source, "")
                            .windows()
                            .getFirst()
                            .panes()
                            .getFirst()
                            .shell());
        }
        Files.writeString(
                source,
                "session_name: shell\nwindows:\n  - panes:\n      - shell: /bin/cat\n        pane_shell: /bin/sh\n");
        Result result = invoke("load", source.toString(), "-d", "--json");
        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("cannot both be set"), result.err());
    }

    @Test
    void paneCommandDefaultsCarryUntilAnExplicitOverride() throws Exception {
        Path source = directory.resolve("defaults.yaml");
        Files.writeString(source, """
                session_name: defaults
                windows:
                  - panes:
                      - enter: false
                        sleep_before: 0.02
                        sleep_after: 0.03
                        shell_command:
                          - first
                          - cmd: second
                            sleep_before: null
                          - cmd: third
                            enter: true
                            sleep_after: 0
                          - fourth
                """);
        Main.Context context = new Main.Context(
                Map.of("HOME", directory.toString()),
                directory,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
        var commands = WorkspacePlan.read(context, source, "")
                .windows()
                .getFirst()
                .panes()
                .getFirst()
                .commands();
        assertEquals(
                List.of(false, false, true, true),
                commands.stream().map(WorkspacePlan.Command::enter).toList());
        assertEquals(
                List.of(20L, 0L, 0L, 0L),
                commands.stream().map(command -> command.before().toMillis()).toList());
        assertEquals(
                List.of(30L, 30L, 0L, 0L),
                commands.stream().map(command -> command.after().toMillis()).toList());
        for (String invalid : List.of("enter: 1", "sleep_before: -1", "sleep_after: []")) {
            Files.writeString(source, "session_name: invalid\nwindows:\n  - panes:\n      - " + invalid + "\n");
            Result result = invoke("load", source.toString(), "-d", "--json");
            assertEquals(1, result.code());
            assertEquals("", result.out());
            assertEquals(
                    "invalid_config",
                    new ObjectMapper().readTree(result.err()).path("code").asText());
        }
    }

    @Test
    void shellVariablesRemainForThePaneToExpand() throws Exception {
        Path source = directory.resolve("commands.yaml");
        Files.writeString(source, "session_name: vars\nwindows:\n  - panes:\n      - 'echo $WORKSPACE_TEST'\n");
        Main.Context context = new Main.Context(
                Map.of("HOME", directory.toString(), "WORKSPACE_TEST", "outer"),
                directory,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
        WorkspacePlan plan = WorkspacePlan.read(context, source, "");
        assertEquals(
                " echo $WORKSPACE_TEST",
                plan.windows()
                        .getFirst()
                        .panes()
                        .getFirst()
                        .commands()
                        .getFirst()
                        .text());
    }
}
