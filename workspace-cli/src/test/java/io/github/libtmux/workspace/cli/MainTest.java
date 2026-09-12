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
