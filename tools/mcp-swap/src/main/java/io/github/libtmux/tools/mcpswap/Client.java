package io.github.libtmux.tools.mcpswap;

import java.nio.file.Path;

record Client(
        String name,
        String binary,
        Path configPath,
        String serverTable,
        ConfigFormat format,
        boolean openCode,
        Scope scope,
        Path repository) {
    Client(String name, Path configPath, String serverTable, ConfigFormat format, boolean openCode) {
        this(
                name,
                name,
                configPath,
                serverTable,
                format,
                openCode,
                Scope.USER,
                Path.of("/").toAbsolutePath());
    }

    Client scoped(Scope requested, Path repo) {
        var normalized = name.equals("claude") ? requested : Scope.USER;
        return new Client(
                name,
                binary,
                configPath,
                serverTable,
                format,
                openCode,
                normalized,
                repo.toAbsolutePath().normalize());
    }

    String label() {
        return name.equals("claude") ? name + ":" + scope.value() : name;
    }
}
