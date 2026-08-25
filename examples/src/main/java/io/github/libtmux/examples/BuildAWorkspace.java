package io.github.libtmux.examples;

import io.github.libtmux.Layout;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Lays out a session the way you would set one up by hand before starting work.
 *
 * <pre>{@code
 * java BuildAWorkspace.java /tmp/libtmux-java-dev/demo/s
 * }</pre>
 */
public final class BuildAWorkspace {

    private static final String ARENA_ARTIFACT = "java-build-a-workspace";

    private BuildAWorkspace() {}

    public static void main(String[] args) {
        Optional<ServerConfig> arena = arenaConfig(System.getenv());
        if (arena.isPresent()) {
            System.out.println("LIBTMUX_ARENA_EVIDENCE=" + runArena(arena.orElseThrow()));
            return;
        }
        run(Path.of(args.length > 0 ? args[0] : "/tmp/libtmux-java-dev/demo/s"));
    }

    static Optional<ServerConfig> arenaConfig(Map<String, String> environment) {
        String descriptor = environment.get("LIBTMUX_ARENA_DESCRIPTOR");
        if (descriptor == null || descriptor.isEmpty()) {
            return Optional.empty();
        }

        String artifact = required(environment, "LIBTMUX_ARENA_ARTIFACT");
        if (!ARENA_ARTIFACT.equals(artifact)) {
            throw new IllegalArgumentException("LIBTMUX_ARENA_ARTIFACT does not select this example");
        }
        return Optional.of(ServerConfig.builder()
                .binary(required(environment, "LIBTMUX_TMUX_BIN"))
                .endpoint(ServerEndpoint.socketPath(Path.of(required(environment, "LIBTMUX_SOCKET_PATH"))))
                .build());
    }

    static String runArena(Map<String, String> environment) {
        return runArena(arenaConfig(environment)
                .orElseThrow(() -> new IllegalStateException("LIBTMUX_ARENA_DESCRIPTOR is not set")));
    }

    private static String runArena(ServerConfig config) {
        if (!(config.endpoint() instanceof ServerEndpoint.SocketPath socket)) {
            throw new IllegalStateException("arena requires a socket path");
        }
        try (Server server = Server.open(config)) {
            run(server);
            String challenge = server.globalOptions()
                    .get("@libtmux_arena_challenge")
                    .filter(value -> !value.isEmpty())
                    .orElseThrow(() -> new IllegalStateException("arena challenge is missing"));
            long serverPid = Long.parseLong(server.expand("#{pid}"));
            String actualSocket = server.expand("#{socket_path}");
            if (!socket.path().toString().equals(actualSocket)) {
                throw new IllegalStateException("arena server socket does not match the requested socket");
            }
            return "{\"artifact\":\"" + ARENA_ARTIFACT + "\",\"challenge\":" + jsonString(challenge)
                    + ",\"schema\":1,\"server_pid\":" + serverPid + ",\"socket_path\":"
                    + jsonString(actualSocket) + "}";
        }
    }

    /** Separated from {@code main} so the suite can run exactly what a reader runs. */
    public static String run(Path socket) {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        // Closing a server closes this client. The tmux server, and the session, outlive the program
        // — which is the whole point of tmux and the reason nothing here kills it.
        try (Server server = Server.open(config)) {
            return run(server);
        }
    }

    static String run(Server server) {
        // One read decides and answers. Asking whether the session exists and then going to
        // look for it is two reads with a gap in between, and the session can arrive or leave
        // inside that gap.
        Session session = server.session("work").orElseGet(() -> server.newSession("work"));

        Window editor = session.newWindow(window -> window.named("editor").detached());
        Pane shell = editor.split(split -> split.toRight());
        shell.sendLine("git status --short");

        editor.selectLayout(Layout.MAIN_VERTICAL);

        return "session " + session.name() + " has "
                + session.refresh().windows().size() + " windows";
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required for arena mode");
        }
        return value;
    }

    static String jsonString(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("JSON evidence cannot contain an unpaired surrogate");
                }
                json.appendCodePoint(value.codePointAt(index));
                index++;
                continue;
            }
            if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("JSON evidence cannot contain an unpaired surrogate");
            }
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < ' ') {
                        appendUnicodeEscape(json, character);
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    private static void appendUnicodeEscape(StringBuilder json, char character) {
        String hex = Integer.toHexString(character);
        json.append("\\u");
        json.append("0000", 0, 4 - hex.length());
        json.append(hex);
    }
}
