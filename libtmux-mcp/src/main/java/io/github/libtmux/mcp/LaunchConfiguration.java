package io.github.libtmux.mcp;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.transport.CommandResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the process-wide socket and tmux configuration before any transport opens.
 *
 * <p>The socket is, in order, {@code --socket}, {@code --socket-name}, {@code LIBTMUX_SOCKET_PATH},
 * {@code LIBTMUX_SOCKET}, and else none: tmux then picks the server itself, which is the one in
 * {@code TMUX} when the launcher runs inside a pane. No server is started or configured on the
 * operator's behalf.
 */
record LaunchConfiguration(
        ServerConfig config, String selector, String selectionProvenance, String declaredConfigurationProvenance) {

    static final String SOCKET_ENV = "LIBTMUX_SOCKET";
    static final String SOCKET_PATH_ENV = "LIBTMUX_SOCKET_PATH";
    static final String CONFIG_ENV = "LIBTMUX_TMUX_CONFIG";

    static LaunchConfiguration resolve(List<String> args, Map<String, String> environment) {
        @Nullable Path flaggedPath = null;
        @Nullable String flaggedName = null;
        String binary = "tmux";
        for (int index = 0; index < args.size(); index++) {
            String flag = args.get(index);
            switch (flag) {
                case "--socket" -> {
                    flaggedPath = absolute(RouteValue.requireSafe(value(args, ++index, flag), flag), flag);
                }
                case "--socket-name" -> {
                    flaggedName = nonempty(RouteValue.requireSafe(value(args, ++index, flag), flag), flag);
                }
                case "--tmux" -> binary = nonempty(RouteValue.requireSafe(value(args, ++index, flag), flag), flag);
                case "--safety" ->
                    throw new IllegalArgumentException(
                            "--safety was retired; select unordered toolsets with " + ToolSurface.TOOLSETS_ENV);
                default -> throw new IllegalArgumentException("unknown argument '" + flag + "'");
            }
        }
        if (flaggedPath != null && flaggedName != null) {
            throw new IllegalArgumentException("--socket and --socket-name are mutually exclusive");
        }
        SocketChoice socket = flaggedPath != null
                ? new SocketChoice(ServerEndpoint.socketPath(flaggedPath), "path:" + flaggedPath, "operator-current")
                : flaggedName != null
                        ? new SocketChoice(
                                ServerEndpoint.namedSocket(flaggedName), "name:" + flaggedName, "operator-current")
                        : socket(environment);

        ConfigChoice configured = configuration(environment.get(CONFIG_ENV));
        ServerConfig.Builder config = ServerConfig.builder().binary(binary).endpoint(socket.endpoint());
        if (configured.path() != null) {
            config.configFile(configured.path());
        }
        return new LaunchConfiguration(
                config.build(), socket.selector(), socket.selectionProvenance(), configured.provenance());
    }

    SocketProfile profile(Server server) {
        Objects.requireNonNull(server, "server");
        CommandResult metadata = server.cmd("display-message", "-p", "#{socket_path}");
        if (!metadata.succeeded()) {
            if (serverAbsent(metadata)) {
                return socketProfile("absent", declaredConfigurationProvenance, fallbackSocketPath());
            }
            throw new IllegalStateException(
                    "could not establish explicit tmux socket provenance: " + String.join("; ", metadata.stderr()));
        }
        String socketPath = oneLine(metadata, "tmux socket path");
        if (socketPath.isBlank()) {
            throw new IllegalStateException("tmux returned an empty socket path during startup");
        }
        return socketProfile("existing", "unknown", reported(socketPath));
    }

    /**
     * Accepts a socket path tmux reported only when it names a file.
     *
     * <p>tmux 3.4 and 3.5 escape a non-printable byte in the socket path when they store it at server
     * start, so a format renders that path as printable text. The rendering carries no control byte
     * for {@link RouteValue#requireSafe} to refuse, and it names no socket, which would freeze a
     * {@code -S} argument that reaches nothing. The server answered over this socket, so the file
     * exists whenever the answer is the path rather than a rendering of it — which is why existence
     * separates the two without depending on which releases escape.
     */
    private static String reported(String socketPath) {
        RouteValue.requireSafe(socketPath, "resolved tmux socket path");
        try {
            if (Files.exists(Path.of(socketPath), LinkOption.NOFOLLOW_LINKS)) {
                return socketPath;
            }
        } catch (InvalidPathException malformed) {
            throw new IllegalStateException("tmux reported an unusable socket path during startup", malformed);
        }
        throw new IllegalStateException("tmux reported a socket path that names no file during startup");
    }

    private SocketProfile socketProfile(String serverState, String configurationProvenance, String resolvedSocketPath) {
        RouteValue.requireSafe(resolvedSocketPath, "resolved tmux socket path");
        return new SocketProfile(
                selector,
                selectionProvenance,
                serverState,
                configurationProvenance,
                resolvedSocketPath,
                SocketProfile.attachCommand(config, resolvedSocketPath));
    }

    private static SocketChoice socket(Map<String, String> environment) {
        @Nullable String configuredPath = unlessBlank(environment.get(SOCKET_PATH_ENV));
        if (configuredPath != null) {
            Path path = absolute(RouteValue.requireSafe(configuredPath, SOCKET_PATH_ENV), SOCKET_PATH_ENV);
            return new SocketChoice(ServerEndpoint.socketPath(path), "path:" + path, "operator-current");
        }
        @Nullable String configuredName = unlessBlank(environment.get(SOCKET_ENV));
        if (configuredName != null) {
            String name = RouteValue.requireSafe(configuredName, SOCKET_ENV);
            if (name.indexOf('/') >= 0) {
                throw new IllegalArgumentException(
                        SOCKET_ENV + " holds a path separator; use " + SOCKET_PATH_ENV + " for a socket path");
            }
            return new SocketChoice(ServerEndpoint.namedSocket(name), "name:" + name, "operator-current");
        }
        return new SocketChoice(ServerEndpoint.defaultSocket(), "inherit", "inherited");
    }

    /** A blank variable counts as unset, as it does for the core and the Python libtmux. */
    private static @Nullable String unlessBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ConfigChoice configuration(@Nullable String configured) {
        if (configured == null) {
            return new ConfigChoice(null, "unknown");
        }
        String value = nonempty(configured, CONFIG_ENV);
        return new ConfigChoice(absolute(value, CONFIG_ENV), "user-configured");
    }

    private String fallbackSocketPath() {
        if (config.endpoint() instanceof ServerEndpoint.SocketPath path) {
            return path.path().toString();
        }
        return "";
    }

    private static String oneLine(CommandResult result, String field) {
        if (result.stdout().size() != 1) {
            throw new IllegalStateException("tmux returned no unambiguous " + field);
        }
        return result.stdout().getFirst();
    }

    private static boolean serverAbsent(CommandResult result) {
        String diagnostic = String.join("\n", result.stderr()).toLowerCase(java.util.Locale.ROOT);
        return diagnostic.contains("no server running on") || diagnostic.contains("no such file or directory");
    }

    private static Path absolute(String value, String source) {
        Path path = Path.of(nonempty(value, source));
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(source + " path must be absolute: " + value);
        }
        return path.normalize();
    }

    private static String nonempty(String value, String source) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(source + " is empty");
        }
        return value;
    }

    private static String value(List<String> args, int index, String flag) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args.get(index);
    }

    private record SocketChoice(ServerEndpoint endpoint, String selector, String selectionProvenance) {}

    private record ConfigChoice(@Nullable Path path, String provenance) {}
}
