package io.github.libtmux.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Startup-frozen facts about the one tmux socket this MCP process exposes. */
record SocketProfile(
        String selector,
        String selectionProvenance,
        String serverState,
        String configurationProvenance,
        String resolvedSocketPath,
        String attachCommand,
        boolean defaultTeardown) {

    Map<String, Object> report() {
        Map<String, Object> socket = new LinkedHashMap<>();
        socket.put("selector", selector);
        socket.put("selectionProvenance", selectionProvenance);
        socket.put("serverState", serverState);
        socket.put("configurationProvenance", configurationProvenance);
        socket.put("namespaceBoundary", "tmux-objects-only");
        return Collections.unmodifiableMap(socket);
    }

    Map<String, Object> connection() {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("socketSelector", selector);
        connection.put("socketProvenance", selectionProvenance);
        connection.put("resolvedSocketPath", resolvedSocketPath);
        connection.put("serverState", serverState);
        connection.put("configurationProvenance", configurationProvenance);
        connection.put("attachCommand", attachCommand);
        return Collections.unmodifiableMap(connection);
    }
}
