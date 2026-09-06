package io.github.libtmux.tools.mcpswap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class McpPreflightTest {
    @TempDir
    Path temporary;

    @Test
    void acceptsInitializeBeforeALongLivedServerExitsAndReapsItsTree() throws Exception {
        var descendant = temporary.resolve("descendant.pid");
        var server = script("long-lived", """
                read request
                sleep 300 &
                child=$!
                printf '%s' "$child" > "$DESCENDANT_PID"
                printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}'
                while :; do sleep 300; done
                """);

        McpPreflight.run(
                new ServerSpec(server.toString(), List.of(), Map.of("DESCENDANT_PID", descendant.toString())),
                System.getenv(),
                Duration.ofSeconds(3),
                1024 * 1024);

        var pid = Long.parseLong(Files.readString(descendant, StandardCharsets.UTF_8));
        assertEventuallyDead(pid);
    }

    @Test
    void keepsStdinOpenUntilTheInitializeResponse() throws Exception {
        var server = temporary.resolve("stdin-open.py");
        Files.writeString(server, """
                #!/usr/bin/env python3
                import select
                import sys
                import time

                sys.stdin.readline()
                closed = select.poll()
                closed.register(sys.stdin, select.POLLHUP)
                time.sleep(0.2)
                if closed.poll(0):
                    raise SystemExit(9)
                print('{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}', flush=True)
                time.sleep(300)
                """, StandardCharsets.UTF_8);
        assertTrue(server.toFile().setExecutable(true));

        McpPreflight.run(new ServerSpec(server.toString(), List.of()), System.getenv(), Duration.ofSeconds(3), 1024);
    }

    @Test
    void rejectsOversizedLongLivedStdoutAndStderrAndReapsTheirTrees() throws Exception {
        for (var stream : List.of("stdout", "stderr")) {
            var descendant = temporary.resolve(stream + ".pid");
            var redirect = stream.equals("stderr") ? "1>&2" : "";
            var server = script("oversized-" + stream, """
                    read request
                    sleep 300 &
                    child=$!
                    printf '%%s' "$child" > "$DESCENDANT_PID"
                    head -c 8192 /dev/zero | tr '\\000' x %s
                    while :; do sleep 300; done
                    """.formatted(redirect));

            var failure = assertThrows(
                    IOException.class,
                    () -> McpPreflight.run(
                            new ServerSpec(
                                    server.toString(), List.of(), Map.of("DESCENDANT_PID", descendant.toString())),
                            System.getenv(),
                            Duration.ofSeconds(3),
                            1024));

            assertTrue(String.valueOf(failure.getMessage()).contains("exceeded"), stream);
            var pid = Long.parseLong(Files.readString(descendant, StandardCharsets.UTF_8));
            assertEventuallyDead(pid);
        }
    }

    @Test
    void requiresTheCompleteInitializeResultEnvelope() throws Exception {
        var invalid = List.of(
                "{\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}",
                "{\"jsonrpc\":\"1.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\" \"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":true}");
        for (var response : invalid) {
            var server = responseServer(response);
            assertThrows(
                    IOException.class,
                    () -> McpPreflight.run(
                            new ServerSpec(server.toString(), List.of()),
                            System.getenv(),
                            Duration.ofSeconds(2),
                            1024));
        }

        var valid = responseServer("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}");
        McpPreflight.run(new ServerSpec(valid.toString(), List.of()), System.getenv(), Duration.ofSeconds(2), 1024);
    }

    @Test
    void rejectsDuplicateInitializeResponseFields() throws Exception {
        var server = responseServer(
                "{\"jsonrpc\":\"1.0\",\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}");

        assertThrows(
                IOException.class,
                () -> McpPreflight.run(
                        new ServerSpec(server.toString(), List.of()), System.getenv(), Duration.ofSeconds(2), 1024));
    }

    @Test
    void passesTheEffectiveSpecEnvironment() throws Exception {
        var server = script("environment", """
                read request
                test "$PREFLIGHT_VALUE" = expected || exit 9
                printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}'
                """);

        McpPreflight.run(
                new ServerSpec(server.toString(), List.of(), Map.of("PREFLIGHT_VALUE", "expected")),
                Map.of("PATH", System.getenv().getOrDefault("PATH", "")),
                Duration.ofSeconds(2),
                1024);
    }

    private Path responseServer(String response) throws IOException {
        return script(
                "response-" + Integer.toUnsignedString(response.hashCode()),
                "read request\nprintf '%s\\n' '" + response + "'\n");
    }

    private Path script(String name, String body) throws IOException {
        var path = temporary.resolve(name + ".sh");
        Files.writeString(path, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }

    private static void assertEventuallyDead(long pid) throws InterruptedException {
        for (var attempt = 0; attempt < 100; attempt++) {
            if (ProcessHandle.of(pid).isEmpty()
                    || !ProcessHandle.of(pid).orElseThrow().isAlive()) {
                return;
            }
            Thread.sleep(10);
        }
        assertFalse(ProcessHandle.of(pid).isPresent()
                && ProcessHandle.of(pid).orElseThrow().isAlive());
    }
}
