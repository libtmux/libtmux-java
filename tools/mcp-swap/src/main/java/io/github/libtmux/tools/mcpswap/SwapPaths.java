package io.github.libtmux.tools.mcpswap;

import java.nio.file.Path;

final class SwapPaths {
    private static final String BACKUP_SUFFIX = ".mcp-swap-backup";

    private SwapPaths() {}

    static Path backup(Client client) {
        return sibling(client.configPath(), client.configPath().getFileName() + BACKUP_SUFFIX);
    }

    static Path state(Client client) {
        var backup = backup(client);
        return sibling(backup, backup.getFileName() + ".state");
    }

    static Path lock(Path home, java.util.Map<String, String> environment) {
        var configured = environment.get("XDG_STATE_HOME");
        var stateHome = configured != null
                        && !configured.isBlank()
                        && Path.of(configured).isAbsolute()
                ? Path.of(configured)
                : home.resolve(".local/state");
        return stateHome.resolve("libtmux-mcp-dev/swap/state.lock");
    }

    private static Path sibling(Path path, String name) {
        var parent = path.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("config path has no parent: " + path);
        }
        return parent.resolve(name);
    }
}
