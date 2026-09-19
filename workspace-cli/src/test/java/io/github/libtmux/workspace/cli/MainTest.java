package io.github.libtmux.workspace.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * The machine vocabulary is closed. Consumers and the other ports read these names, so a
     * failure may not invent one, and one may not be added to the set without this saying so.
     */
    @Test
    void everyMachineFailureCodeComesFromTheSharedSet() throws Exception {
        var shared = java.util.Set.of(
                "workspace_not_found",
                "invalid_workspace",
                "unsupported_key",
                "session_not_found",
                "session_mismatch",
                "tmux_unavailable",
                "tmux_failed",
                "script_failed",
                "destination_exists",
                "usage");
        Path unsupported = directory.resolve("unsupported.yaml");
        Files.writeString(unsupported, "session_name: codes\nbogus: 1\nwindows: [{}]\n");
        Path malformed = directory.resolve("malformed.yaml");
        Files.writeString(malformed, "a: [\n");
        Path valid = directory.resolve("valid.yaml");
        Files.writeString(valid, "session_name: valid\nwindows: [{}]\n");
        for (String[] invocation : List.of(
                new String[] {"load", directory.resolve("gone.yaml").toString(), "-d", "--json"},
                new String[] {"load", malformed.toString(), "-d", "--json"},
                new String[] {"load", unsupported.toString(), "-d", "--json"},
                new String[] {"load", valid.toString(), "-d", "-8", "--json"},
                new String[] {"load", valid.toString(), "-d", "--json"},
                new String[] {"load", valid.toString(), "-d", "--json", "--log-file", directory.toString()},
                new String[] {"shell", "-c", "print(1)", "--json"},
                new String[] {"search", "[", "--json"})) {
            Result result = invoke(invocation);
            assertFalse(result.code() == 0, String.join(" ", invocation));
            String code = new ObjectMapper().readTree(result.err()).path("code").asText();
            assertTrue(shared.contains(code), code + " from " + String.join(" ", invocation));
        }
        var documented = java.util.Set.of("interrupted", "log_unavailable");
        var declared = java.util.Arrays.stream(Machine.Code.values())
                .map(Machine.Code::wire)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(declared.containsAll(shared), declared.toString());
        var extra = new java.util.HashSet<>(declared);
        extra.removeAll(shared);
        assertEquals(documented, extra, "a code outside the shared set must be one the README names");
    }

    /** A missing workspace file is `workspace_not_found`. */
    @Test
    void missingWorkspaceFileReportsWorkspaceNotFound() throws Exception {
        Result result = invoke("load", directory.resolve("missing.yaml").toString(), "-d", "--json");
        assertEquals(1, result.code(), result.toString());
        assertEquals(
                "workspace_not_found",
                new ObjectMapper().readTree(result.err()).path("code").asText());
    }

    /** Malformed YAML is `invalid_workspace`. */
    @Test
    void malformedYamlReportsInvalidWorkspace() throws Exception {
        Path source = directory.resolve("bad.yaml");
        Files.writeString(source, "a: [\n");
        Result result = invoke("load", source.toString(), "-d", "--json");
        assertEquals(1, result.code(), result.toString());
        assertEquals(
                "invalid_workspace",
                new ObjectMapper().readTree(result.err()).path("code").asText());
    }

    /** An unknown top-level key is `unsupported_key`, and the message
     * suggests the `x-` prefix that exempts a key. */
    @Test
    void unsupportedTopLevelKeyReportsUnsupportedKeyAndSuggestsXPrefix() throws Exception {
        Path source = directory.resolve("bogus.yaml");
        Files.writeString(source, "session_name: x\nbogus: 1\nwindows: [{}]\n");
        Result result = invoke("load", source.toString(), "-d", "--json");
        assertEquals(1, result.code(), result.toString());
        var diagnostic = new ObjectMapper().readTree(result.err());
        assertEquals("unsupported_key", diagnostic.path("code").asText());
        assertTrue(diagnostic.path("message").asText().contains("x-"), result.err());
    }

    /** A missing tmux executable is `tmux_unavailable`, not the python probe's own code. */
    @Test
    void missingTmuxExecutableReportsTmuxUnavailable() throws Exception {
        Path source = directory.resolve("any.yaml");
        Files.writeString(source, "session_name: any\nwindows: [{}]\n");
        Result result = invoke("load", source.toString(), "-d", "--json");
        assertEquals(1, result.code(), result.toString());
        var diagnostic = new ObjectMapper().readTree(result.err());
        assertEquals("tmux_unavailable", diagnostic.path("code").asText());
        assertEquals(1, diagnostic.path("schema_version").asInt());
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
        assertEquals("usage", diagnostic.path("code").asText());
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
                "usage", new ObjectMapper().readTree(result.err()).path("code").asText());
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
        var diagnostic = new ObjectMapper().readTree(result.err());
        assertEquals("script_failed", diagnostic.path("code").asText());
        assertTrue(diagnostic.path("message").asText().contains("tmuxp 1.74.0 is required"), result.err());
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
                assertEquals("invalid_workspace", value.path("code").asText());
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
                    "usage",
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
                    "invalid_workspace",
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
                "destination_exists",
                new ObjectMapper().readTree(rejected.err()).path("code").asText());
        assertEquals(
                0,
                invoke("convert", source.toString(), "--json", "--save-to", target.toString(), "--force")
                        .code());
        assertEquals(document, new ObjectMapper().readTree(Files.readString(target)));
    }

    /** A save that cannot ask for confirmation is a usage refusal: the request cannot be carried out. */
    @Test
    void convertWithoutYesAndNoTerminalIsARefusalAboutTheInvocation() throws Exception {
        Path source = directory.resolve("confirm.yaml");
        Files.writeString(source, "session_name: confirm\nwindows: []\n");
        Result result = invoke("convert", source.toString(), "--save-to", "confirm.json");
        assertEquals(2, result.code(), result.toString());
        assertFalse(Files.exists(directory.resolve("confirm.json")));
        assertEquals("Error: confirmation requires a terminal; pass --yes\n", result.err());
    }

    /** Saving relies on a hard link to refuse an existing destination, which not every store has. */
    @Test
    void aDestinationWithoutHardLinksIsStillWrittenAndStillProtected() throws Exception {
        var document = Documents.JSON.createObjectNode().put("session_name", "linkless");
        try (var store =
                java.nio.file.FileSystems.newFileSystem(directory.resolve("store.zip"), Map.of("create", "true"))) {
            Path target = store.getPath("/workspace.yaml");

            Documents.write(target, document, "yaml", false);

            assertTrue(Files.readString(target).contains("linkless"));
            assertThrows(
                    java.nio.file.FileAlreadyExistsException.class,
                    () -> Documents.write(target, document, "yaml", false));
        }
    }

    /** A capture drain outlives the command it reported for, and can reach the reporter after it. */
    @Test
    void reportingAfterCloseNeitherWritesNorComplains() throws Exception {
        Path logFile = directory.resolve("late.log");
        var err = new ByteArrayOutputStream();
        var parsed = Arguments.create()
                .parseArgs("--log-level", "debug", "load", "workspace.yaml", "-d", "--log-file", logFile.toString());
        Main.Context context = new Main.Context(
                Map.of("HOME", directory.toString()),
                directory,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                err);
        Reporter report = new Reporter(context, parsed);
        report.event(Machine.Event.STARTED, Documents.JSON.createObjectNode());
        long written = Files.size(logFile);
        String reported = err.toString(StandardCharsets.UTF_8);
        report.close();

        report.event(
                Machine.Event.SCRIPT_OUTPUT, Documents.JSON.createObjectNode().put("stream", "stdout"));

        assertEquals(written, Files.size(logFile));
        assertEquals(reported, err.toString(StandardCharsets.UTF_8));
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
        // A missing start_directory is a warning, not a refusal; this harness has no tmux on
        // PATH, so the load still fails, but past workspace validation and for a different reason.
        Result load = invoke("load", saved.toString(), "-d", "--json");
        assertTrue(load.err().contains("start_directory_missing"), load.toString());
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
                "script_failed",
                new ObjectMapper().readTree(result.err()).path("code").asText());
    }

    @Test
    void missingPythonBridgeReportsItsRuntimeRequirement() throws Exception {
        Result result = invoke(Map.of("TMUX_WORKSPACE_PYTHON", "/missing/python"), "shell", "-c", "print(1)", "--json");
        assertEquals(1, result.code());
        assertEquals("", result.out());
        var diagnostic = new ObjectMapper().readTree(result.err());
        assertEquals("script_failed", diagnostic.path("code").asText());
        assertTrue(diagnostic.path("message").asText().contains("/missing/python"), result.err());
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
        for (String catalog : List.of("[]", "false", "{pane_readiness: sometimes}", "{pane_readiness: []}")) {
            Files.writeString(
                    source,
                    "session_name: readiness\nworkspace_builder_options: " + catalog
                            + "\nwindows:\n  - panes: [null]\n");
            Result result = invoke("load", source.toString(), "-d", "--json");
            assertEquals(1, result.code());
            assertEquals("", result.out());
            assertEquals(
                    "invalid_workspace",
                    new ObjectMapper().readTree(result.err()).path("code").asText());
            assertTrue(result.err().contains("workspace_builder_options"), result.err());
        }
        Files.writeString(
                source,
                "session_name: readiness\nworkspace_builder_options: {unknown: true}\nwindows:\n  - panes: [null]\n");
        WorkspacePlan unknownKey = WorkspacePlan.read(context, source, "");
        assertEquals(1, unknownKey.warnings().size(), unknownKey.warnings().toString());
        assertEquals(
                "unsupported_builder_option", unknownKey.warnings().getFirst().code());
        assertTrue(
                unknownKey.warnings().getFirst().message().contains("workspace_builder_options.unknown"),
                unknownKey.warnings().toString());
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
                    "invalid_workspace",
                    new ObjectMapper().readTree(result.err()).path("code").asText());
        }
    }

    /** tmuxp expands a pane command through expandshell before sending it, as it does a value. */
    @Test
    void oneShellVariableMeansOneThingAcrossAWorkspace() throws Exception {
        Path source = directory.resolve("commands.yaml");
        Files.writeString(source, """
                session_name: vars
                options:
                  '@banner': $WORKSPACE_TEST
                windows:
                  - panes:
                      - 'echo $WORKSPACE_TEST'
                """);
        Main.Context context = new Main.Context(
                Map.of("HOME", directory.toString(), "WORKSPACE_TEST", "outer"),
                directory,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
        WorkspacePlan plan = WorkspacePlan.read(context, source, "");
        assertEquals("outer", plan.options().get("@banner"));
        assertEquals(
                " echo outer",
                plan.windows()
                        .getFirst()
                        .panes()
                        .getFirst()
                        .commands()
                        .getFirst()
                        .text());
    }

    /** tmuxp freeze writes focus as the quoted string 'true', not a YAML boolean; load must accept both. */
    @Test
    void focusAcceptsTheQuotedStringTmuxpFreezeWrites() throws Exception {
        Path source = directory.resolve("focus.yaml");
        Main.Context context = new Main.Context(
                Map.of("HOME", directory.toString()),
                directory,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
        for (String quoted : List.of("'true'", "'false'")) {
            Files.writeString(
                    source,
                    "session_name: focus\nwindows:\n  - focus: " + quoted + "\n    panes:\n      - focus: " + quoted
                            + "\n");
            var window = WorkspacePlan.read(context, source, "").windows().getFirst();
            boolean expected = quoted.equals("'true'");
            assertEquals(expected, window.focus(), quoted);
            assertEquals(expected, window.panes().getFirst().focus(), quoted);
        }
        Files.writeString(source, "session_name: focus\nwindows:\n  - focus: maybe\n    panes: [null]\n");
        Result invalid = invoke("load", source.toString(), "-d", "--json");
        assertEquals(1, invalid.code());
        assertTrue(
                new ObjectMapper()
                        .readTree(invalid.err())
                        .path("message")
                        .asText()
                        .contains("focus must be a boolean"),
                invalid.err());
    }

    /**
     * tmux rewrites, refuses or keeps ':' and '.' depending on its version, and even kept verbatim a
     * bare -t name misreads the delimiter as a window separator, so no spelling survives every
     * supported tmux.
     */
    @Test
    void sessionNameRefusesTmuxTargetSeparators() throws Exception {
        Path source = directory.resolve("h8.yaml");
        for (String bad : List.of("a:b", "a.b")) {
            Files.writeString(source, "session_name: \"" + bad + "\"\nwindows:\n  - panes: [null]\n");
            Result result = invoke("load", source.toString(), "-d", "--json");
            assertEquals(1, result.code(), result.toString());
            var diagnostic = new ObjectMapper().readTree(result.err());
            assertEquals("invalid_workspace", diagnostic.path("code").asText());
            String character = bad.contains(":") ? ":" : ".";
            assertTrue(diagnostic.path("message").asText().contains("'" + character + "'"), result.err());
        }
    }

    /** A missing start_directory is a warning that still loads, matching tmuxp; tmux falls back to $HOME. */
    @Test
    void missingStartDirectoryWarnsInsteadOfRefusing() throws Exception {
        Path source = directory.resolve("m2.yaml");
        Path missing = directory.resolve("missing");
        Files.writeString(source, "session_name: m2\nstart_directory: " + missing + "\nwindows:\n  - panes: [null]\n");
        Main.Context context = new Main.Context(
                Map.of("HOME", directory.toString()),
                directory,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
        WorkspacePlan plan = WorkspacePlan.read(context, source, "");
        assertEquals(missing, plan.directory());
        assertEquals(1, plan.warnings().size(), plan.warnings().toString());
        assertEquals("start_directory_missing", plan.warnings().getFirst().code());
        assertTrue(
                plan.warnings().getFirst().message().contains("not a directory"),
                plan.warnings().toString());
    }

    /** A workspace file's own directory must never supply the default when start_directory is absent. */
    @Test
    void defaultPaneDirectoryIsCwdNotTheWindowOrPaneParent() throws Exception {
        Path documentDirectory = Files.createDirectories(directory.resolve("docs"));
        Path source = documentDirectory.resolve("h9.yaml");
        Files.writeString(source, "session_name: h9\nwindows:\n  - panes: [null]\n");
        Main.Context context = new Main.Context(
                Map.of("HOME", directory.toString()),
                directory,
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
        WorkspacePlan plan = WorkspacePlan.read(context, source, "");
        assertEquals(directory, plan.directory());
        assertEquals(directory, plan.windows().getFirst().panes().getFirst().directory());
    }

    /** A Python traceback is not a sentence; the last stderr line carries the exception, not "Traceback". */
    @Test
    void pythonRuntimeFailureReportsOneSentenceNotATraceback() throws Exception {
        Path script = directory.resolve("fake-python.sh");
        Files.writeString(script, """
                #!/bin/sh
                echo 'Traceback (most recent call last):' >&2
                echo '  File "<string>", line 1, in <module>' >&2
                echo "ModuleNotFoundError: No module named 'tmuxp'" >&2
                exit 1
                """);
        java.nio.file.Files.setPosixFilePermissions(
                script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Result result = invoke(Map.of("TMUX_WORKSPACE_PYTHON", script.toString()), "shell", "-c", "print(1)", "--json");
        assertEquals(1, result.code());
        var diagnostic = new ObjectMapper().readTree(result.err());
        assertEquals("script_failed", diagnostic.path("code").asText());
        String message = diagnostic.path("message").asText();
        assertFalse(message.contains("Traceback"), message);
        assertTrue(message.contains("ModuleNotFoundError"), message);
        assertTrue(message.contains("TMUX_WORKSPACE_PYTHON"), message);
    }

    @Test
    void shellHelpDocumentsThePythonInterpreterVariable() {
        Result result = invoke("shell", "--help");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("TMUX_WORKSPACE_PYTHON"), result.out());
    }

    @Test
    void debugInfoHumanModeIsReadableTextNotTheJsonDocument() {
        Result human = invoke("debug-info");
        Result json = invoke("debug-info", "--json");
        assertEquals(0, human.code(), human.err());
        assertEquals(0, json.code(), json.err());
        assertTrue(json.out().strip().startsWith("{"), json.out());
        assertFalse(human.out().strip().startsWith("{"), human.out());
        assertTrue(human.out().contains("tmux-workspace"), human.out());
        assertFalse(human.out().equals(json.out()));
    }

    @Test
    void generateAcceptsZshAndFishAlongsideBash() throws Exception {
        for (String shell : List.of("zsh", "fish")) {
            Result result = invoke("--generate", shell);
            assertEquals(0, result.code(), result.err());
            assertFalse(result.out().strip().startsWith("{"), result.out());
            assertTrue(result.out().contains("tmux-workspace"), result.out());
            Result machine = invoke("--generate", shell, "--json");
            assertEquals(0, machine.code(), machine.err());
            var artifact = new ObjectMapper().readTree(machine.out());
            assertEquals(shell, artifact.path("format").asText());
            assertEquals(result.out(), artifact.path("script").asText());
        }
    }
}
