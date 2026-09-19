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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        String script = """
                import errno, os, pty, select, shlex, signal, sys, time
                root, completion = sys.argv[1:]
                buffers, ready = root + '/buffers', root + '/ready'
                cases = [
                    ('tmux-workspace imp', 'tmux-workspace import '),
                    ('tmux-workspace import team', 'tmux-workspace import teamocil '),
                    ('tmux-workspace --color al', 'tmux-workspace --color always '),
                    ('tmux-workspace --color n', 'tmux-workspace --color never '),
                    ('tmux-workspace --log-level de', 'tmux-workspace --log-level debug '),
                    ('tmux-workspace --generate ba', 'tmux-workspace --generate bash '),
                    ('tmux-workspace load --color al', 'tmux-workspace load --color always '),
                    ('tmux-workspace load --log-level de', 'tmux-workspace load --log-level debug '),
                    ('tmux-workspace freeze -f j', 'tmux-workspace freeze -f json '),
                    ('tmux-workspace freeze --workspace-format y', 'tmux-workspace freeze --workspace-format yaml '),
                    ('tmux-workspace --color b', 'tmux-workspace --color b'),
                    ('tmux-workspace --generate al', 'tmux-workspace --generate al'),
                ]
                setup = 'source ' + shlex.quote(completion) + "; bind 'set keyseq-timeout 1'; "
                setup += '''_capture() { printf '%s\\\\0' "$READLINE_LINE" >> ''' + shlex.quote(buffers)
                setup += '''; READLINE_LINE=; READLINE_POINT=0; }; bind -x '"\\\\C-x":_capture'; '''
                setup += "PS1='prompt> '; : > " + shlex.quote(ready)
                pid, fd = pty.fork()
                if pid == 0:
                    os.chdir(root)
                    os.execve('/bin/bash', ['bash', '--noprofile', '--norc'],
                        dict(os.environ, TERM='xterm', HISTFILE='/dev/null'))
                trace = bytearray()
                joined = False
                exit_status = None
                def until(predicate):
                    end = time.monotonic() + 4
                    while time.monotonic() < end:
                        if predicate(): return
                        if select.select([fd], [], [], .01)[0]:
                            try: trace.extend(os.read(fd, 65536))
                            except OSError as error:
                                if error.errno != errno.EIO: raise
                                time.sleep(.01)
                    raise AssertionError('completion observation timed out: ' + repr(trace[-2000:]))
                def values():
                    try:
                        with open(buffers, 'rb') as stream: return stream.read().split(b'\\0')[:-1]
                    except FileNotFoundError: return []
                def exited():
                    global joined, exit_status
                    child, current = os.waitpid(pid, os.WNOHANG)
                    if child:
                        joined, exit_status = True, current
                    return joined
                try:
                    os.write(fd, (setup + '\\n').encode())
                    until(lambda: os.path.exists(ready))
                    for i, (line, expected) in enumerate(cases):
                        os.write(fd, (line + '\\t\\x18').encode())
                        until(lambda: len(values()) > i)
                        actual = values()[i].decode()
                        assert actual == expected, (line, actual, expected)
                    os.write(fd, b'exit\\n')
                    until(exited)
                    assert os.waitstatus_to_exitcode(exit_status) == 0
                finally:
                    if not joined:
                        try: os.killpg(pid, signal.SIGKILL)
                        except ProcessLookupError: pass
                        os.waitpid(pid, 0)
                    os.close(fd)
                    with open(root + '/terminal.raw', 'wb') as stream: stream.write(trace)
                """;
        Process probe = new ProcessBuilder("python3", "-c", script, directory.toString(), completion.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            assertTrue(probe.waitFor(15, TimeUnit.SECONDS));
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
        Path project = Files.createDirectories(directory.resolve("project/child"));
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
                    assertTrue(process.waitFor(15, TimeUnit.SECONDS), "installed import/load did not finish");
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
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "capture did not finish");
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
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "reload did not finish");
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
        String script = """
                import array, fcntl, os, signal, subprocess, sys, termios, time
                reader, writer = os.pipe()
                capacity = fcntl.fcntl(writer, fcntl.F_SETPIPE_SZ, 4096)
                child = subprocess.Popen(sys.argv[1:], stdout=writer, stderr=subprocess.PIPE,
                    stdin=subprocess.DEVNULL)
                os.close(writer)
                try:
                    size = array.array('i', [0])
                    until = time.monotonic() + 4
                    while child.poll() is None and time.monotonic() < until:
                        fcntl.ioctl(reader, termios.FIONREAD, size, True)
                        if size[0] == capacity: break
                        time.sleep(.01)
                    assert size[0] == capacity and child.poll() is None, size[0]
                    child.send_signal(signal.SIGINT)
                    assert child.wait(timeout=1.5) == 130
                finally:
                    if child.poll() is None:
                        child.kill()
                        child.wait()
                    os.close(reader)
                    child.stderr.close()
                """;
        var builder = command("ls", "--full", "--json");
        var argv = new ArrayList<>(List.of("python3", "-c", script));
        argv.addAll(builder.command());
        builder.command(argv).redirectError(ProcessBuilder.Redirect.INHERIT);
        Process probe = builder.start();
        try {
            assertTrue(probe.waitFor(7, TimeUnit.SECONDS), "undrained pipe probe did not finish");
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
        String script = """
                import fcntl, os, pty, select, signal, struct, subprocess, sys, termios, time
                (launcher, source, socket, output, error, capture,
                    terminal_error, disabled, panel_lines, cancelled) = sys.argv[1:]
                master, slave = pty.openpty()
                fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 24, 100, 0, 0))
                def terminal():
                    os.setsid()
                    fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
                args = [launcher, 'load', source, '-d', '-S', socket, '-f', '/dev/null',
                    '--progress-format', 'PROGRESS_{session}_{window_total}_{session_pane_total}',
                    '--progress-lines', panel_lines]
                if disabled == 'true': args.append('--no-progress')
                chunks = []
                sent = False
                with open(output, 'wb') as out, open(error, 'wb') as err:
                    child = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=out,
                        stderr=slave if terminal_error == 'true' else err,
                        preexec_fn=terminal, pass_fds=(slave,))
                    try:
                        until = time.monotonic() + 5
                        while child.poll() is None and time.monotonic() < until:
                            if select.select([master], [], [], .025)[0]: chunks.append(os.read(master, 65536))
                            if cancelled == 'true' and not sent and b''.join(chunks).count(b'PROGRESS_progress_1_2') >= 3:
                                child.send_signal(signal.SIGINT)
                                sent = True
                        assert child.wait(timeout=1) == (130 if cancelled == 'true' else 0)
                        while select.select([master], [], [], 0)[0]: chunks.append(os.read(master, 65536))
                    finally:
                        if child.poll() is None:
                            child.kill()
                            child.wait()
                        os.close(slave)
                        os.close(master)
                with open(capture, 'wb') as target: target.write(b''.join(chunks))
                """;
        var builder = command();
        builder.command(
                "python3",
                "-c",
                script,
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
                assertTrue(probe.waitFor(7, TimeUnit.SECONDS));
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
        String script = """
                import fcntl, os, pty, subprocess, sys, termios
                master, slave = pty.openpty()
                def terminal():
                    os.setsid()
                    fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
                env = dict(os.environ, EDITOR="/bin/sh -c '[ -t 0 ] && [ -t 1 ] && printf EDITOR_TTY' editor")
                with open(sys.argv[3], 'wb') as output:
                    child = subprocess.Popen([sys.argv[1], 'edit', sys.argv[2], '--json'], stdin=slave,
                        stdout=output, stderr=slave, env=env, preexec_fn=terminal)
                    code = child.wait(timeout=5)
                os.close(slave)
                os.close(master)
                sys.exit(code)
                """;
        Process process = new ProcessBuilder(
                        "python3",
                        "-c",
                        script,
                        System.getProperty("workspace.cli.launcher"),
                        source.toString(),
                        output.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        assertTrue(process.waitFor(7, TimeUnit.SECONDS));
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
        String script = """
                import fcntl, os, pty, subprocess, sys, termios, time
                launcher, source, socket, tmux, destination, redirected = sys.argv[1:]
                redirected = redirected == 'true'
                master, slave = pty.openpty()
                def terminal():
                    os.setsid()
                    fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
                env = dict(os.environ, TERM='xterm', LIBTMUX_TEST_TMUX=tmux)
                env.pop('TMUX', None)
                env.pop('TMUX_PANE', None)
                connected = False
                written = 0
                with open(destination, 'wb') as output:
                    child = subprocess.Popen([launcher, 'load', source, '-S', socket, '-f', '/dev/null'],
                        stdin=subprocess.DEVNULL if redirected else slave, stdout=output,
                        stderr=output if redirected else slave, env=env, preexec_fn=terminal, pass_fds=(slave,))
                    try:
                        until = time.monotonic() + 5
                        name = None
                        while child.poll() is None and time.monotonic() < until:
                            clients = subprocess.run([tmux, '-S', socket, 'list-clients', '-F', '#{client_name}'], capture_output=True, text=True)
                            if clients.returncode == 0 and clients.stdout.strip():
                                connected = True
                                name = clients.stdout.strip().splitlines()[0]
                                break
                            time.sleep(.025)
                        if connected:
                            # Give a working client time to draw before measuring what it wrote.
                            time.sleep(.3)
                            report = subprocess.run(
                                [tmux, '-S', socket, 'display-message', '-p', '-t', name, '#{client_written}'],
                                capture_output=True, text=True)
                            written = int(report.stdout.strip() or '0')
                            subprocess.run([tmux, '-S', socket, 'detach-client', '-t', name], check=True)
                        code = child.wait(timeout=3)
                    finally:
                        if child.poll() is None:
                            child.kill()
                            child.wait()
                os.close(slave)
                os.close(master)
                # Redirected stdio carries no terminal, so tmux itself must refuse to attach, the
                # same as any other program asked to draw a screen on a file. A real terminal must
                # both connect and actually render: a few hundred bytes is the blank-screen bug.
                ok = (not connected and code != 0) if redirected else (connected and code == 0 and written > 450)
                if not ok:
                    print('connected=%s code=%s written=%s' % (connected, code, written), file=sys.stderr)
                sys.exit(0 if ok else 1)
                """;
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            try {
                Process process = new ProcessBuilder(
                                "python3",
                                "-c",
                                script,
                                System.getProperty("workspace.cli.launcher"),
                                source.toString(),
                                socket.toString(),
                                System.getProperty("libtmux.tmux", "tmux"),
                                directory.resolve("status.log").toString(),
                                Boolean.toString(redirected))
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
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
        String script = """
                import fcntl, os, pty, shlex, subprocess, sys, termios, time
                launcher, source, socket, tmux, scratch = sys.argv[1:]
                env = dict(os.environ, TERM='xterm', LIBTMUX_TEST_TMUX=tmux)
                env.pop('TMUX', None)
                env.pop('TMUX_PANE', None)
                prefix = [tmux, '-S', socket]
                clients = []
                try:
                    for name in ('origin', 'other'):
                        master, slave = pty.openpty()
                        os.set_blocking(master, False)
                        def terminal(slave=slave):
                            os.setsid()
                            fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
                        child = subprocess.Popen(prefix + ['attach-session', '-t', name],
                            stdin=slave, stdout=slave, stderr=slave, env=env, preexec_fn=terminal)
                        clients.append((master, slave, os.ttyname(slave), child))
                    def attached():
                        for master, slave, tty, child in clients:
                            try: os.read(master, 65536)
                            except (BlockingIOError, OSError): pass
                        rows = subprocess.check_output(prefix + ['list-clients', '-F', '#{client_name} #{session_name}'], text=True)
                        return dict(row.rsplit(' ', 1) for row in rows.splitlines())
                    until = time.monotonic() + 4
                    while len(attached()) != 2 and time.monotonic() < until:
                        time.sleep(.025)
                    assert len(attached()) == 2
                    pane = subprocess.check_output(prefix + ['display-message', '-p', '-t', 'origin:', '#{pane_id}'], text=True).strip()
                    until = time.monotonic() + 4
                    while time.monotonic() < until:
                        attached()
                        screen = subprocess.check_output(prefix + ['capture-pane', '-p', '-t', pane], text=True)
                        if 'workspace-ready>' in screen: break
                        time.sleep(.025)
                    assert 'workspace-ready>' in screen, screen
                    status = os.path.join(scratch, 'exit-status')
                    command = shlex.join(['env', 'LIBTMUX_TEST_TMUX=' + tmux,
                        launcher, 'load', source, '-S', socket])
                    command += ' </dev/null >' + shlex.quote(os.path.join(scratch, 'stdout'))
                    command += ' 2>' + shlex.quote(os.path.join(scratch, 'stderr'))
                    command += '; printf %s $? >' + shlex.quote(status)
                    subprocess.run(prefix + ['send-keys', '-t', pane, '-l', command], check=True)
                    subprocess.run(prefix + ['send-keys', '-t', pane, 'Enter'], check=True)
                    until = time.monotonic() + 5
                    while not os.path.exists(status) and time.monotonic() < until:
                        attached()
                        time.sleep(.025)
                    if not os.path.exists(status):
                        print(subprocess.check_output(prefix + ['display-message', '-p', '-t', pane,
                            '#{pane_current_command}:#{pane_dead}:#{pane_in_mode}:#{cursor_x},#{cursor_y}'], text=True), file=sys.stderr)
                        print(subprocess.check_output(prefix + ['capture-pane', '-p', '-t', pane], text=True), file=sys.stderr)
                        for name in ('stdout', 'stderr'):
                            path = os.path.join(scratch, name)
                            if os.path.exists(path): print(name, open(path).read(), file=sys.stderr)
                    assert open(status).read() == '0', open(os.path.join(scratch, 'stderr')).read()
                    state = attached()
                    assert state[clients[0][2]] == 'switched', state
                    assert state[clients[1][2]] == 'other', state
                finally:
                    subprocess.run(prefix + ['kill-server'], capture_output=True)
                    for master, slave, tty, child in clients:
                        if child.poll() is None: child.wait(timeout=2)
                        os.close(slave)
                        os.close(master)
                """;
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
                                "-c",
                                script,
                                System.getProperty("workspace.cli.launcher"),
                                source.toString(),
                                socket.toString(),
                                System.getProperty("libtmux.tmux", "tmux"),
                                directory.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                assertTrue(child.waitFor(12, TimeUnit.SECONDS));
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
        String script = """
                import fcntl, os, pty, subprocess, sys, termios, time
                launcher, source, socket, tmux, destination = sys.argv[1:]
                prefix = [tmux, '-S', socket]
                master, slave = pty.openpty()
                os.set_blocking(master, False)
                def terminal():
                    os.setsid()
                    fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
                env_client = dict(os.environ, TERM='xterm', LIBTMUX_TEST_TMUX=tmux)
                env_client.pop('TMUX', None)
                env_client.pop('TMUX_PANE', None)
                client = subprocess.Popen(prefix + ['attach-session', '-t', 'keeper'],
                    stdin=slave, stdout=slave, stderr=slave, env=env_client, preexec_fn=terminal)
                def sessions():
                    try: os.read(master, 65536)
                    except (BlockingIOError, OSError): pass
                    rows = subprocess.check_output(prefix + ['list-clients', '-F', '#{client_name} #{session_name}'], text=True)
                    return dict(row.rsplit(' ', 1) for row in rows.splitlines() if row)
                try:
                    until = time.monotonic() + 5
                    state = sessions()
                    while not state and time.monotonic() < until:
                        time.sleep(.025)
                        state = sessions()
                    assert len(state) == 1, state
                    pid = subprocess.check_output(prefix + ['display-message', '-p', '#{pid}'], text=True).strip()
                    # Exactly what run-shell hands its command: TMUX resolves, TMUX_PANE and the
                    # terminal do not.
                    env = dict(os.environ, TMUX=socket + ',' + pid + ',0', LIBTMUX_TEST_TMUX=tmux)
                    env.pop('TMUX_PANE', None)
                    with open(destination, 'wb') as output:
                        runshell = subprocess.Popen([launcher, 'load', source, '-S', socket],
                            stdin=subprocess.DEVNULL, stdout=output, stderr=output, env=env)
                        code = runshell.wait(timeout=5)
                    until = time.monotonic() + 5
                    switched = False
                    while time.monotonic() < until:
                        state = sessions()
                        if state and list(state.values())[0] != 'keeper':
                            switched = True
                            break
                        time.sleep(.025)
                    if not (code == 0 and switched):
                        print('code=%s switched=%s state=%s' % (code, switched, state), file=sys.stderr)
                    sys.exit(0 if code == 0 and switched else 1)
                finally:
                    subprocess.run(prefix + ['kill-server'], capture_output=True)
                    if client.poll() is None:
                        client.wait(timeout=2)
                    os.close(slave)
                    os.close(master)
                """;
        Path diagnostics = directory.resolve("runshell-diagnostics.log");
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            server.newSession("keeper");
            try {
                Process process = new ProcessBuilder(
                                "python3",
                                "-c",
                                script,
                                System.getProperty("workspace.cli.launcher"),
                                source.toString(),
                                socket.toString(),
                                System.getProperty("libtmux.tmux", "tmux"),
                                directory.resolve("runshell-output").toString())
                        .redirectError(diagnostics.toFile())
                        .start();
                boolean finished = process.waitFor(15, TimeUnit.SECONDS);
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
        String script = """
                import fcntl, os, pty, shlex, subprocess, sys, termios, time
                launcher, source, socket, tmux, scratch = sys.argv[1:]
                env = dict(os.environ, TERM='xterm', LIBTMUX_TEST_TMUX=tmux)
                env.pop('TMUX', None)
                env.pop('TMUX_PANE', None)
                prefix = [tmux, '-S', socket]
                master, slave = pty.openpty()
                os.set_blocking(master, False)
                def terminal():
                    os.setsid()
                    fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
                child = subprocess.Popen(prefix + ['attach-session', '-t', 'keeper'],
                    stdin=slave, stdout=slave, stderr=slave, env=env, preexec_fn=terminal)
                def screen():
                    try: os.read(master, 65536)
                    except (BlockingIOError, OSError): pass
                    return subprocess.check_output(prefix + ['capture-pane', '-p', '-t', 'keeper'], text=True)
                def wait_for(needle, budget):
                    until = time.monotonic() + budget
                    text = screen()
                    while needle not in text and time.monotonic() < until:
                        time.sleep(.025)
                        text = screen()
                    return text
                try:
                    text = wait_for('workspace-ready>', 4)
                    assert 'workspace-ready>' in text, text
                    status = os.path.join(scratch, 'exit-status')
                    # Neither stream is redirected: the prompt (on stderr) must stay visible on the
                    # pane to be read and answered by a real keystroke.
                    command = shlex.join(['env', 'LIBTMUX_TEST_TMUX=' + tmux, launcher, 'load', source])
                    command += '; printf %s $? >' + shlex.quote(status)
                    subprocess.run(prefix + ['send-keys', '-t', 'keeper', '-l', command], check=True)
                    subprocess.run(prefix + ['send-keys', '-t', 'keeper', 'Enter'], check=True)
                    text = wait_for('switch (y)', 5)
                    assert 'switch (y)' in text, text
                    # A real keystroke, not piped stdin: several ports skip prompting on a pipe.
                    subprocess.run(prefix + ['send-keys', '-t', 'keeper', '-l', 'n'], check=True)
                    subprocess.run(prefix + ['send-keys', '-t', 'keeper', 'Enter'], check=True)
                    until = time.monotonic() + 5
                    text = screen()
                    while not os.path.exists(status) and time.monotonic() < until:
                        time.sleep(.025)
                        text = screen()
                    assert os.path.exists(status), text
                    assert open(status).read() == '0', text
                    built = subprocess.run(prefix + ['has-session', '-t', 'from-prompt'], capture_output=True).returncode == 0
                    assert built, 'answering n must still build the session, detached'
                    final = subprocess.check_output(prefix + ['list-clients', '-F', '#{session_name}'], text=True).strip()
                    assert final == 'keeper', final
                finally:
                    subprocess.run(prefix + ['kill-server'], capture_output=True)
                    if child.poll() is None:
                        child.wait(timeout=2)
                    os.close(slave)
                    os.close(master)
                """;
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .build()) {
            server.newSession(spec ->
                    spec.named("keeper").running("/bin/sh", "-i").env("ENV", "").env("PS1", "workspace-ready> "));
            try {
                Process child = new ProcessBuilder(
                                "python3",
                                "-c",
                                script,
                                System.getProperty("workspace.cli.launcher"),
                                source.toString(),
                                socket.toString(),
                                System.getProperty("libtmux.tmux", "tmux"),
                                directory.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                assertTrue(child.waitFor(15, TimeUnit.SECONDS));
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
        String script = """
                import fcntl, os, pty, shlex, subprocess, sys, termios, time
                launcher, source, socket, tmux, scratch = sys.argv[1:]
                env = dict(os.environ, TERM='xterm', LIBTMUX_TEST_TMUX=tmux)
                env.pop('TMUX', None)
                env.pop('TMUX_PANE', None)
                prefix = [tmux, '-S', socket]
                master, slave = pty.openpty()
                os.set_blocking(master, False)
                def terminal():
                    os.setsid()
                    fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
                child = subprocess.Popen(prefix + ['attach-session', '-t', 'keeper'],
                    stdin=slave, stdout=slave, stderr=slave, env=env, preexec_fn=terminal)
                def screen():
                    try: os.read(master, 65536)
                    except (BlockingIOError, OSError): pass
                    return subprocess.check_output(prefix + ['capture-pane', '-p', '-t', 'keeper'], text=True)
                def wait_for(needle, budget):
                    until = time.monotonic() + budget
                    text = screen()
                    while needle not in text and time.monotonic() < until:
                        time.sleep(.025)
                        text = screen()
                    return text
                try:
                    text = wait_for('workspace-ready>', 4)
                    assert 'workspace-ready>' in text, text
                    status = os.path.join(scratch, 'exit-status')
                    # Neither stream is redirected: the prompt (on stderr) must stay visible on the
                    # pane to be read and answered by a real keystroke.
                    command = shlex.join(['env', 'LIBTMUX_TEST_TMUX=' + tmux, launcher, 'load', source])
                    command += '; printf %s $? >' + shlex.quote(status)
                    subprocess.run(prefix + ['send-keys', '-t', 'keeper', '-l', command], check=True)
                    subprocess.run(prefix + ['send-keys', '-t', 'keeper', 'Enter'], check=True)
                    text = wait_for('already running', 5)
                    assert 'already running' in text, text
                    assert 'switch (y)' not in text, 'the existing-session question must not double as the new-session one'
                    subprocess.run(prefix + ['send-keys', '-t', 'keeper', '-l', 'n'], check=True)
                    subprocess.run(prefix + ['send-keys', '-t', 'keeper', 'Enter'], check=True)
                    until = time.monotonic() + 5
                    text = screen()
                    while not os.path.exists(status) and time.monotonic() < until:
                        time.sleep(.025)
                        text = screen()
                    assert os.path.exists(status), text
                    assert open(status).read() == '0', text
                    windows = subprocess.check_output(prefix + ['list-windows', '-t', 'home'], text=True).splitlines()
                    assert len(windows) == 1, windows
                    final = subprocess.check_output(prefix + ['list-clients', '-F', '#{session_name}'], text=True).strip()
                    assert final == 'keeper', final
                finally:
                    subprocess.run(prefix + ['kill-server'], capture_output=True)
                    if child.poll() is None:
                        child.wait(timeout=2)
                    os.close(slave)
                    os.close(master)
                """;
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
                                "-c",
                                script,
                                System.getProperty("workspace.cli.launcher"),
                                source.toString(),
                                socket.toString(),
                                System.getProperty("libtmux.tmux", "tmux"),
                                directory.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                assertTrue(child.waitFor(15, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
            } finally {
                if (server.isAlive()) server.killServer();
            }
        }
    }

    @Test
    void aFullLogCannotReplaceTheOriginalLoadResult() throws Exception {
        Path source = directory.resolve("failed.yaml");
        Path socket = directory.resolve("failed-log");
        Files.writeString(
                source,
                "session_name: partial-log\nwindows:\n  - options:\n      not-a-tmux-option: invalid\n    panes: [null]\n");
        String script = """
                import resource, signal, subprocess, sys
                def limited():
                    signal.signal(signal.SIGXFSZ, signal.SIG_IGN)
                    resource.setrlimit(resource.RLIMIT_FSIZE, (0, 0))
                child = subprocess.run(sys.argv[1:], capture_output=True, preexec_fn=limited)
                sys.stdout.buffer.write(child.stdout)
                sys.stderr.buffer.write(child.stderr)
                sys.exit(child.returncode)
                """;
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
        var arguments = new ArrayList<>(List.of("python3", "-c", script));
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
                assertTrue(process.waitFor(8, TimeUnit.SECONDS));
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
