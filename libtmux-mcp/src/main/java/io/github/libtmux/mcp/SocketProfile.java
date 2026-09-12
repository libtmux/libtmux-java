package io.github.libtmux.mcp;

import io.github.libtmux.ServerConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Startup-frozen facts about the one tmux socket this MCP process exposes. */
record SocketProfile(
        String selector,
        String selectionProvenance,
        String serverState,
        String configurationProvenance,
        String resolvedSocketPath,
        String attachCommand,
        boolean defaultTeardown) {

    /** Tokens a POSIX shell reads as themselves, so quoting them only makes the command harder to read. */
    private static final Pattern ALREADY_SAFE = Pattern.compile("[\\w@%+=:,./-]+");

    /**
     * The command an operator runs to attach to the server this process is using.
     *
     * <p>The socket path tmux itself reported wins whenever there is one. {@code -S} with an absolute
     * path reaches that one server from any shell, while {@code -L name} is resolved again under the
     * operator's own {@code TMUX_TMPDIR} and user — possibly a different directory, and so a different
     * server that happens to share the name. The socket path is the access boundary {@code
     * SECURITY.md} describes, which makes it the thing to hand over.
     *
     * <p>Without a reported path, because the server was not running at startup, the command is
     * built from {@link io.github.libtmux.ServerEndpoint#flags()}, so every kind of endpoint selects
     * its server and none is left out by a branch that did not know about it.
     *
     * @param resolvedSocketPath the path tmux reported for its socket, or empty when it reported none
     */
    static String attachCommand(ServerConfig config, String resolvedSocketPath) {
        StringBuilder command = new StringBuilder(quote(config.binaryPath())).append(" -N");
        List<String> selector =
                resolvedSocketPath.isBlank() ? config.endpoint().flags() : List.of("-S", resolvedSocketPath);
        selector.forEach(flag -> command.append(' ').append(quote(flag)));
        return command.append(" attach").toString();
    }

    /**
     * Quotes a token for display in a shell command, leaving alone the ones that need no quoting.
     *
     * <p>The safe set is the one {@code shlex.quote} uses. Being conservative about what counts as
     * safe matters more than brevity here: this string is printed for a person to paste.
     */
    private static String quote(String value) {
        return ALREADY_SAFE.matcher(value).matches() ? value : "'" + value.replace("'", "'\"'\"'") + "'";
    }

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
