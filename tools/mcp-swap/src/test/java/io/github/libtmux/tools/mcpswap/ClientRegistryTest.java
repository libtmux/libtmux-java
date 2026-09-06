package io.github.libtmux.tools.mcpswap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClientRegistryTest {
    private static final List<String> NAMES =
            List.of("claude", "codex", "cursor", "gemini", "grok", "agy", "opencode", "pi");
    private static final List<String> BINARIES =
            List.of("claude", "codex", "cursor-agent", "gemini", "grok", "agy", "opencode", "pi");

    @Test
    void resolvesEveryClientUnderTheSuppliedHome() {
        var home = Path.of("/test/home");
        var clients = ClientRegistry.knownClients(home, Map.of("XDG_CONFIG_HOME", "/test/config"));

        assertEquals(NAMES, clients.stream().map(Client::name).toList());
        assertEquals(BINARIES, clients.stream().map(Client::binary).toList());
        assertEquals(home.resolve(".claude.json"), clients.getFirst().configPath());
        assertEquals(
                Path.of("/test/config/opencode/opencode.jsonc"), clients.get(6).configPath());
        assertEquals(home.resolve(".pi/agent/mcp.json"), clients.getLast().configPath());
    }

    @Test
    void ignoresARelativeXdgConfigHome() {
        var home = Path.of("/test/home");

        var clients = ClientRegistry.knownClients(home, Map.of("XDG_CONFIG_HOME", "relative"));

        assertEquals(
                home.resolve(".config/opencode/opencode.jsonc"), clients.get(6).configPath());
    }

    @Test
    void selectsInCanonicalOrderWithAliasesAndDuplicates() {
        var clients = ClientRegistry.knownClients(Path.of("/test/home"), Map.of());

        var selected = ClientRegistry.select(clients, List.of("cursor,antigravity", "claude", "cursor"));

        assertEquals(
                List.of("claude", "cursor", "agy"),
                selected.stream().map(Client::name).toList());
    }

    @Test
    void rejectsEmptyAndUnknownSelectors() {
        var clients = ClientRegistry.knownClients(Path.of("/test/home"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> ClientRegistry.select(clients, List.of("claude,")));
        assertThrows(IllegalArgumentException.class, () -> ClientRegistry.select(clients, List.of("clod")));
    }

    @Test
    void normalizesAllClientOrderings() {
        var clients = ClientRegistry.knownClients(Path.of("/test/home"), Map.of());
        var orderings = new int[] {0};

        permutations(new ArrayList<>(NAMES), ordering -> {
            assertEquals(
                    NAMES,
                    ClientRegistry.select(clients, ordering).stream()
                            .map(Client::name)
                            .toList());
            orderings[0]++;
        });

        assertEquals(40_320, orderings[0]);
    }

    private static void permutations(List<String> remaining, java.util.function.Consumer<List<String>> check) {
        if (remaining.isEmpty()) {
            check.accept(List.of());
            return;
        }
        for (int index = 0; index < remaining.size(); index++) {
            var suffix = new ArrayList<>(remaining);
            var first = suffix.remove(index);
            permutations(suffix, rest -> {
                var ordering = new ArrayList<String>(rest.size() + 1);
                ordering.add(first);
                ordering.addAll(rest);
                check.accept(ordering);
            });
        }
    }
}
