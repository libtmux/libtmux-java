package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A tool call answered by a real daemon below the floor that has replaced a 3.3a one on the same
 * socket. The version the server learned must not be trusted: the call is refused with the remedy,
 * promptly, and the old daemon's sessions are left alone. The rejection lanes of the tmux matrix
 * run this class beside the library's own; elsewhere it is skipped.
 */
final class OldDaemonToolCallTest {

    private static final Duration BOUND = Duration.ofSeconds(15);

    @Test
    void aToolCallAfterAnOlderDaemonReplacesAKnownOneIsRefusedWithTheRemedy() throws Exception {
        String lane = System.getProperty("libtmux.tmux.expected", "");
        assumeTrue(
                "true".equals(System.getProperty("libtmux.tmux.rejection")), "only a rejection lane has an old daemon");
        String client = System.getProperty("libtmux.tmux.client");
        assertNotNull(client, "lane " + lane + " needs libtmux.tmux.client, a tmux of 3.3a or newer");
        String daemon = System.getProperty("libtmux.tmux", "tmux");
        Path root = Files.createTempDirectory(Path.of("/tmp/libtmux-java-test"), "mcp-old-daemon-");
        Path socket = root.resolve("s");
        ServerConfig config = ServerConfig.builder()
                .binary(client)
                .endpoint(ServerEndpoint.socketPath(socket))
                .configFile(Path.of("/dev/null"))
                .defaultTimeout(Duration.ofSeconds(60))
                .build();
        // The profile was read at startup, while the daemon still passed.
        ToolSurface surface = ToolSurface.resolve(
                Map.of(ToolSurface.TOOLSETS_ENV, "", ToolSurface.TOOLS_ENV, "list_sessions,create_session"),
                new SocketProfile(
                        "path:" + socket,
                        "operator-current",
                        "existing",
                        "unknown",
                        socket.toString(),
                        "tmux -N -S '" + socket + "' attach",
                        false));
        Wire output = new Wire();
        try (Server server = Server.open(config);
                PipedInputStream input = new PipedInputStream();
                PipedOutputStream stdin = new PipedOutputStream(input)) {
            raw(client, socket, "new-session", "-d", "-s", "known");
            McpSyncServer mcp = TmuxMcpServer.overStdio(server, input, output, surface, () -> {});
            try {
                send(stdin, initialize());
                assertTrue(output.await(1, 5), "initialization did not answer");
                send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

                JsonNode known = call(stdin, output, 2, "list_sessions");
                assertEquals(false, known.path("isError").asBoolean(), known.toString());

                raw(client, socket, "kill-server");
                for (int wait = 0; wait < 100 && Files.exists(socket); wait++) {
                    TimeUnit.MILLISECONDS.sleep(50);
                }
                raw(daemon, socket, "new-session", "-d", "-s", "sentinel");

                for (int id = 3; id <= 4; id++) {
                    String tool = id == 3 ? "list_sessions" : "create_session";
                    int call = id;
                    JsonNode refused = assertTimeoutPreemptively(BOUND, () -> call(stdin, output, call, tool));
                    assertEquals(true, refused.path("isError").asBoolean(), refused.toString());
                    assertEquals(
                            "UNSUPPORTED_FEATURE",
                            refused.path("_meta").path("error_code").asText(),
                            refused.toString());
                    String message = refused.path("content").get(0).path("text").asText();
                    assertTrue(message.contains("requires tmux 3.3a or newer"), message);
                    assertTrue(message.contains("restart any running server"), message);
                }
            } finally {
                mcp.close();
            }
            assertEquals(List.of("sentinel"), raw(daemon, socket, "list-sessions", "-F", "#{session_name}"));
        } finally {
            try {
                raw(daemon, socket, "kill-server");
            } catch (IOException | AssertionError gone) {
                // The server may already be gone.
            }
            Files.deleteIfExists(socket);
            Files.deleteIfExists(root);
        }
    }

    private static JsonNode call(PipedOutputStream stdin, Wire output, int id, String tool) throws Exception {
        send(
                stdin,
                Answers.JSON.writeValueAsString(Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        "call-" + id,
                        "method",
                        "tools/call",
                        "params",
                        Map.of(
                                "name",
                                tool,
                                "arguments",
                                tool.equals("create_session") ? Map.of("session_name", "x") : Map.of()))));
        assertTrue(output.await(id, 60), tool + " did not answer");
        String line = output.lines().stream()
                .filter(l -> l.contains("\"call-" + id + "\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no response to " + tool));
        return Answers.JSON.readTree(line).path("result");
    }

    private static void send(PipedOutputStream stdin, String line) throws IOException {
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private static String initialize() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                + ProtocolVersions.MCP_2025_11_25
                + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
    }

    /** The binary's own client, outside the library. */
    private static List<String> raw(String binary, Path socket, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(binary, "-S", socket.toString(), "-f", "/dev/null"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), command.toString());
        if (process.exitValue() != 0) {
            throw new AssertionError(command + " exited " + process.exitValue() + ": " + output);
        }
        return output.isEmpty() ? List.of() : List.of(output.stripTrailing().split("\n"));
    }

    /** Collects protocol lines and lets a test wait for the nth. */
    private static final class Wire extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int lines;

        @Override
        public synchronized void write(int value) {
            bytes.write(value);
            if (value == '\n') {
                lines++;
                notifyAll();
            }
        }

        @Override
        public synchronized void write(byte[] values, int offset, int length) {
            for (int index = offset; index < offset + length; index++) {
                write(values[index]);
            }
        }

        synchronized List<String> lines() {
            return bytes.toString(StandardCharsets.UTF_8).lines().toList();
        }

        synchronized boolean await(int expected, long seconds) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            long remaining = end - System.nanoTime();
            while (lines < expected && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
                remaining = end - System.nanoTime();
            }
            return lines >= expected;
        }
    }
}
