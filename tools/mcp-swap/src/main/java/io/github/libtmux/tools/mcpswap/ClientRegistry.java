package io.github.libtmux.tools.mcpswap;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ClientRegistry {
    private ClientRegistry() {}

    static List<Client> knownClients(Path home, Map<String, String> environment) {
        var xdg = environment.get("XDG_CONFIG_HOME");
        var configHome =
                xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute() ? Path.of(xdg) : home.resolve(".config");
        return List.of(
                new Client("claude", home.resolve(".claude.json"), "mcpServers", ConfigFormat.JSON, false),
                new Client("codex", home.resolve(".codex/config.toml"), "mcp_servers", ConfigFormat.TOML, false),
                new Client("cursor", home.resolve(".cursor/mcp.json"), "mcpServers", ConfigFormat.JSON, false),
                new Client("gemini", home.resolve(".gemini/settings.json"), "mcpServers", ConfigFormat.JSON, false),
                new Client("grok", home.resolve(".grok/config.toml"), "mcp_servers", ConfigFormat.TOML, false),
                new Client(
                        "agy", home.resolve(".gemini/config/mcp_config.json"), "mcpServers", ConfigFormat.JSON, false),
                new Client("opencode", configHome.resolve("opencode/opencode.jsonc"), "mcp", ConfigFormat.JSONC, true),
                new Client("pi", home.resolve(".pi/agent/mcp.json"), "mcpServers", ConfigFormat.JSONC, false));
    }

    static List<Client> select(List<Client> clients, List<String> selectors) {
        if (selectors.isEmpty()) {
            return clients;
        }
        Set<String> known = new HashSet<>();
        clients.forEach(client -> known.add(client.name()));
        Set<String> wanted = new HashSet<>();
        for (var selector : selectors) {
            for (var part : selector.split(",", -1)) {
                var name = part.trim();
                if (name.equals("antigravity")) {
                    name = "agy";
                }
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("client name was empty");
                }
                if (!known.contains(name)) {
                    throw new IllegalArgumentException("unknown client " + name);
                }
                wanted.add(name);
            }
        }
        return clients.stream().filter(client -> wanted.contains(client.name())).toList();
    }
}
