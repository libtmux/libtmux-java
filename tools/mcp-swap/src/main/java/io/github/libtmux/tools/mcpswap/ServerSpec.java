package io.github.libtmux.tools.mcpswap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record ServerSpec(String command, List<String> arguments, Map<String, String> environment) {
    ServerSpec {
        arguments = List.copyOf(arguments);
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
    }

    ServerSpec(String command, List<String> arguments) {
        this(command, arguments, Map.of());
    }

    ServerSpec withEnvironment(Map<String, String> existing) {
        if (environment.containsKey("LIBTMUX_SAFETY")) {
            throw new IllegalArgumentException("LIBTMUX_SAFETY has been removed; use LIBTMUX_TOOLSETS");
        }
        Map<String, String> merged = new LinkedHashMap<>(existing);
        if (environment.containsKey("LIBTMUX_TOOLSETS")) {
            merged.remove("LIBTMUX_SAFETY");
        }
        merged.putAll(environment);
        return new ServerSpec(command, arguments, merged);
    }
}
