package io.github.libtmux.tools.mcpswap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

final class ConfigCodecTest {
    private static final ObjectMapper JSONC = new ObjectMapper(JsonFactory.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build());
    private static final ServerSpec SERVER =
            new ServerSpec("/opt/libtmux-java/bin/libtmux-mcp", List.of("--socket", "/tmp/test/s"));

    @Test
    void roundTripsTheServerRouteForAllEightClients() {
        var clients = ClientRegistry.knownClients(Path.of("/test/home"), Map.of());
        var seen = new ArrayList<String>();
        var routed =
                new ServerSpec(SERVER.command(), SERVER.arguments(), Map.of("LIBTMUX_TOOLSETS", "inspect,execute"));

        for (var client : clients) {
            var original =
                    switch (client.format()) {
                        case JSON -> "{}\n";
                        case JSONC -> "{\n  // keep\n}\n";
                        case TOML -> "title = \"keep\"\n";
                    };
            var updated =
                    ConfigCodec.update(client, original.getBytes(StandardCharsets.UTF_8), "name.with.dot", routed);
            assertEquals(
                    routed, ConfigCodec.read(client, updated, "name.with.dot").orElseThrow());
            seen.add(client.name());
        }

        assertEquals(List.of("claude", "codex", "cursor", "gemini", "grok", "agy", "opencode", "pi"), seen);
    }

    @Test
    void updatesJsonWithoutDroppingUnrelatedValues() throws Exception {
        var client = client("claude", ConfigFormat.JSON, false);
        var raw = "{\n  \"unrelated\": {\"keep\": true}\n}\n".getBytes(StandardCharsets.UTF_8);

        var updated = ConfigCodec.update(client, raw, "tmux", SERVER);

        var root = new ObjectMapper().readTree(updated);
        assertTrue(root.path("unrelated").path("keep").asBoolean());
        assertStandardEntry(root.path("mcpServers").path("tmux"));
        assertEquals('\n', updated[updated.length - 1]);
    }

    @Test
    void addsJsoncTableWithoutChangingCommentsOrTrailingComma() throws Exception {
        var client = client("opencode", ConfigFormat.JSONC, true);
        var original = "{\n  // root comment stays\n  \"unrelated\": {\"keep\": true},\n}\n";

        var updated = new String(
                ConfigCodec.update(client, original.getBytes(StandardCharsets.UTF_8), "tmux", SERVER),
                StandardCharsets.UTF_8);

        assertTrue(updated.contains("// root comment stays"));
        assertTrue(updated.endsWith(",\n}\n"));
        var root = JSONC.readTree(updated);
        assertTrue(root.path("unrelated").path("keep").asBoolean());
        assertEquals("local", root.path("mcp").path("tmux").path("type").asText());
        assertEquals(
                List.of("/opt/libtmux-java/bin/libtmux-mcp", "--socket", "/tmp/test/s"),
                JSONC.convertValue(root.path("mcp").path("tmux").path("command"), List.class));
    }

    @Test
    void replacesJsoncEntryWithoutDroppingItsComment() throws Exception {
        var client = client("opencode", ConfigFormat.JSONC, true);
        var original = """
                {
                  "mcp": {
                    "tmux": {
                      "type": "local",
                      // pinned locally; keep this rationale
                      "command": ["old", "server"],
                    },
                    "other": {"type": "local", "command": ["echo", "keep"]},
                  },
                }
                """;

        var updated = new String(
                ConfigCodec.update(client, original.getBytes(StandardCharsets.UTF_8), "tmux", SERVER),
                StandardCharsets.UTF_8);

        assertTrue(updated.contains("// pinned locally; keep this rationale"));
        var root = JSONC.readTree(updated);
        assertEquals(
                "echo", root.path("mcp").path("other").path("command").get(0).asText());
        assertEquals(
                "/opt/libtmux-java/bin/libtmux-mcp",
                root.path("mcp").path("tmux").path("command").get(0).asText());
    }

    @Test
    void updatesTomlWithoutDroppingUnrelatedBytesOrComments() {
        var client = client("codex", ConfigFormat.TOML, false);
        var original = "title = \"keep\"\n\n# unrelated comment\n[other]\nvalue = 1\n";

        var updated = new String(
                ConfigCodec.update(client, original.getBytes(StandardCharsets.UTF_8), "tmux", SERVER),
                StandardCharsets.UTF_8);

        assertTrue(updated.startsWith(original));
        assertTrue(updated.contains("[mcp_servers.\"tmux\"]"));
        assertEquals("keep", Toml.parse(updated).getString("title"));
        assertEquals("/opt/libtmux-java/bin/libtmux-mcp", Toml.parse(updated).getString("mcp_servers.tmux.command"));
    }

    @Test
    void replacesTomlEntryAndKeepsItsComment() {
        var client = client("codex", ConfigFormat.TOML, false);
        var original = """
                title = "keep"

                [mcp_servers.tmux]
                # keep this rationale
                command = "old"
                args = ["server"]

                [mcp_servers.other]
                command = "echo"
                """;

        var updated = new String(
                ConfigCodec.update(client, original.getBytes(StandardCharsets.UTF_8), "tmux", SERVER),
                StandardCharsets.UTF_8);

        assertTrue(updated.contains("# keep this rationale"));
        assertEquals("echo", Toml.parse(updated).getString("mcp_servers.other.command"));
        assertEquals("/opt/libtmux-java/bin/libtmux-mcp", Toml.parse(updated).getString("mcp_servers.tmux.command"));
        var arguments = Toml.parse(updated).getArray("mcp_servers.tmux.args");
        assertNotNull(arguments);
        assertEquals("--socket", arguments.getString(0));
    }

    @Test
    void replacesRetiredSafetyWithoutDroppingJsonEnvironment() throws Exception {
        var client = client("claude", ConfigFormat.JSON, false);
        var original = """
                {
                  "mcpServers": {
                    "tmux": {
                      "command": "old",
                      "args": [],
                      "env": {
                        "LIBTMUX_SAFETY": "destructive",
                        "LIBTMUX_TOOLSETS": "inspect,execute",
                        "KEEP": "yes"
                      }
                    }
                  }
                }
                """;

        var updated = ConfigCodec.update(client, original.getBytes(StandardCharsets.UTF_8), "tmux", SERVER);

        var entry = new ObjectMapper().readTree(updated).path("mcpServers").path("tmux");
        assertFalse(entry.path("env").has("LIBTMUX_SAFETY"));
        assertEquals(
                "inspect,execute", entry.path("env").path("LIBTMUX_TOOLSETS").asText());
        assertEquals("yes", entry.path("env").path("KEEP").asText());
    }

    @Test
    void preservesTomlEnvironmentAndItsCommentsWhileRemovingSafety() {
        var client = client("codex", ConfigFormat.TOML, false);
        var original = """
                [mcp_servers.tmux]
                command = "old"
                args = []

                [mcp_servers.tmux.env]
                # keep this environment rationale
                LIBTMUX_SAFETY = "readonly"
                LIBTMUX_TOOLSETS = "inspect"
                KEEP = "yes"

                [mcp_servers.other]
                command = "echo"
                args = []
                """;

        var updated = new String(
                ConfigCodec.update(client, original.getBytes(StandardCharsets.UTF_8), "tmux", SERVER),
                StandardCharsets.UTF_8);

        var parsed = Toml.parse(updated);
        assertTrue(updated.contains("# keep this environment rationale"));
        assertFalse(updated.contains("LIBTMUX_SAFETY"));
        assertEquals("inspect", parsed.getString("mcp_servers.tmux.env.LIBTMUX_TOOLSETS"));
        assertEquals("yes", parsed.getString("mcp_servers.tmux.env.KEEP"));
        assertEquals("echo", parsed.getString("mcp_servers.other.command"));
    }

    @Test
    void refusesToGuessAToolsetWhenOnlyRetiredSafetyExists() {
        var client = client("cursor", ConfigFormat.JSON, false);
        var original = """
                {"mcpServers":{"tmux":{"command":"old","args":[],"env":{"LIBTMUX_SAFETY":"destructive"}}}}
                """;

        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ConfigCodec.update(client, original.getBytes(StandardCharsets.UTF_8), "tmux", SERVER));

        assertTrue(String.valueOf(failure.getMessage()).contains("LIBTMUX_TOOLSETS"));
    }

    @Test
    void readsTheOptionalArgumentListAsEmpty() {
        var json = client("cursor", ConfigFormat.JSON, false);
        var toml = client("codex", ConfigFormat.TOML, false);

        assertEquals(
                new ServerSpec("server", List.of()),
                ConfigCodec.read(
                                json,
                                "{\"mcpServers\":{\"tmux\":{\"command\":\"server\"}}}".getBytes(StandardCharsets.UTF_8),
                                "tmux")
                        .orElseThrow());
        assertEquals(
                new ServerSpec("server", List.of()),
                ConfigCodec.read(
                                toml,
                                "[mcp_servers.tmux]\ncommand = \"server\"\n".getBytes(StandardCharsets.UTF_8),
                                "tmux")
                        .orElseThrow());
    }

    @Test
    void rejectsMalformedUtf8InsteadOfRewritingReplacementCharacters() {
        var clients = List.of(
                client("claude", ConfigFormat.JSON, false),
                client("opencode", ConfigFormat.JSONC, true),
                client("codex", ConfigFormat.TOML, false));
        var prefixes = List.of("{\"keep\":\"", "{// keep ", "# keep ");
        var suffixes = List.of("\"}\n", "\n}\n", "\nvalue = 1\n");

        for (int index = 0; index < clients.size(); index++) {
            var client = clients.get(index);
            var malformed = malformedUtf8(prefixes.get(index), suffixes.get(index));
            assertThrows(IllegalArgumentException.class, () -> ConfigCodec.update(client, malformed, "tmux", SERVER));
            assertThrows(IllegalArgumentException.class, () -> ConfigCodec.read(client, malformed, "tmux"));
        }
    }

    private static Client client(String name, ConfigFormat format, boolean openCode) {
        var table = openCode ? "mcp" : format == ConfigFormat.TOML ? "mcp_servers" : "mcpServers";
        return new Client(name, Path.of("/test/config"), table, format, openCode);
    }

    private static byte[] malformedUtf8(String prefix, String suffix) {
        var before = prefix.getBytes(StandardCharsets.UTF_8);
        var after = suffix.getBytes(StandardCharsets.UTF_8);
        var result = new byte[before.length + 2 + after.length];
        System.arraycopy(before, 0, result, 0, before.length);
        result[before.length] = (byte) 0xc3;
        result[before.length + 1] = 0x28;
        System.arraycopy(after, 0, result, before.length + 2, after.length);
        return result;
    }

    private static void assertStandardEntry(JsonNode entry) {
        assertEquals("/opt/libtmux-java/bin/libtmux-mcp", entry.path("command").asText());
        assertEquals("--socket", entry.path("args").get(0).asText());
        assertEquals("/tmp/test/s", entry.path("args").get(1).asText());
    }
}
