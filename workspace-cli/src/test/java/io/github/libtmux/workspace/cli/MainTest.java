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
