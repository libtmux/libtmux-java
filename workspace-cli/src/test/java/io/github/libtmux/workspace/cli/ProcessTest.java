package io.github.libtmux.workspace.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
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
                assertEquals(!cancelled, stdout.contains("Loaded"), stdout);
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
                with open(destination, 'wb') as output:
                    child = subprocess.Popen([launcher, 'load', source, '-S', socket, '-f', '/dev/null'],
                        stdin=subprocess.DEVNULL if redirected else slave, stdout=output,
                        stderr=output if redirected else slave, env=env, preexec_fn=terminal, pass_fds=(slave,))
                    try:
                        until = time.monotonic() + 5
                        while child.poll() is None and time.monotonic() < until:
                            clients = subprocess.run([tmux, '-S', socket, 'list-clients', '-F', '#{client_name}'], capture_output=True, text=True)
                            if clients.returncode == 0 and clients.stdout.strip():
                                connected = True
                                subprocess.run([tmux, '-S', socket, 'detach-client', '-t', clients.stdout.strip()], check=True)
                                break
                            time.sleep(.025)
                        code = child.wait(timeout=3)
                    finally:
                        if child.poll() is None:
                            child.kill()
                            child.wait()
                os.close(slave)
                os.close(master)
                sys.exit(0 if connected and code == 0 else 1)
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
                    status = os.path.join(scratch, 'exit-status')
                    command = shlex.join([launcher, 'load', source, '-S', socket])
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
                    assert open(status).read() == '0'
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
            server.newSession("origin");
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

    @Test
    void aFullLogCannotReplaceTheOriginalPartialLoadResult() throws Exception {
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
                assertEquals("partial", terminal.path("status").asText());
                var effects = terminal.path("errors").path(0).path("effects");
                assertEquals("windows", effects.path("stage").asText());
                assertEquals(1, effects.path("window_ids").size());
                assertEquals(1, effects.path("pane_ids").size());
                assertTrue(error.contains("not-a-tmux-option"), error);
                assertTrue(error.contains("\"code\":\"log_file\""), error);
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
                if (server.isAlive()) server.killServer();
            }
        }
    }
}
