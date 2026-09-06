package io.github.libtmux.tools.mcpswap;

import java.util.List;

record ServerSpec(String command, List<String> arguments) {
    ServerSpec {
        arguments = List.copyOf(arguments);
    }
}
