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
        Map<String, String> merged = new LinkedHashMap<>(existing);
        var retired = merged.remove("LIBTMUX_SAFETY") != null;
        merged.putAll(environment);
        retired |= merged.remove("LIBTMUX_SAFETY") != null;
        if (retired && !merged.containsKey("LIBTMUX_TOOLSETS")) {
            throw new IllegalArgumentException("LIBTMUX_SAFETY has been removed; supply LIBTMUX_TOOLSETS explicitly");
        }
        return new ServerSpec(command, arguments, merged);
    }
}
