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
}
