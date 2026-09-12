package io.github.libtmux.examples;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The arena activation contract every example shares: a non-empty {@code
 * LIBTMUX_ARENA_DESCRIPTOR} is the only signal, the reported artifact id then has to match the
 * caller's own, and a completed run reports one verified evidence record on stdout.
 */
final class ArenaSupport {

    private ArenaSupport() {}

    /** Empty when the descriptor is absent, so ordinary command-line use is untouched. */
    static Optional<ServerConfig> config(Map<String, String> environment, String artifact) {
        String descriptor = environment.get("LIBTMUX_ARENA_DESCRIPTOR");
        if (descriptor == null || descriptor.isEmpty()) {
            return Optional.empty();
        }

        String reported = required(environment, "LIBTMUX_ARENA_ARTIFACT");
        if (!artifact.equals(reported)) {
            throw new IllegalArgumentException("LIBTMUX_ARENA_ARTIFACT does not select this example");
        }
        return Optional.of(ServerConfig.builder()
                .binary(required(environment, "LIBTMUX_TMUX_BIN"))
                .endpoint(ServerEndpoint.socketPath(Path.of(required(environment, "LIBTMUX_SOCKET_PATH"))))
                .build());
    }

    /**
     * Opens the lent server, runs {@code body} against it, and returns one verified evidence record.
     *
     * <p>Verifies the reported socket path equals the one requested before returning, so evidence
     * never asserts a server the arena did not actually lend.
     */
    static String run(String artifact, ServerConfig config, Consumer<Server> body) {
        if (!(config.endpoint() instanceof ServerEndpoint.SocketPath socket)) {
            throw new IllegalStateException("arena requires a socket path");
        }
        try (Server server = Server.open(config)) {
            body.accept(server);
            String challenge = server.globalOptions()
                    .get("@libtmux_arena_challenge")
                    .filter(value -> !value.isEmpty())
                    .orElseThrow(() -> new IllegalStateException("arena challenge is missing"));
            long serverPid = Long.parseLong(server.expand("#{pid}"));
            String actualSocket = server.expand("#{socket_path}");
            if (!socket.path().toString().equals(actualSocket)) {
                throw new IllegalStateException("arena server socket does not match the requested socket");
            }
            return "{\"artifact\":\"" + artifact + "\",\"challenge\":" + jsonString(challenge)
                    + ",\"schema\":1,\"server_pid\":" + serverPid + ",\"socket_path\":"
                    + jsonString(actualSocket) + "}";
        }
    }

    /**
     * The session an example seeds and reuses by name, so a lent server's own session is never the
     * one an example silently acts on.
     */
    static Session ownSession(Server server, String name) {
        // One read decides and answers. Asking whether the session exists and then going to
        // look for it is two reads with a gap in between, and the session can arrive or leave
        // inside that gap.
        return server.session(name).orElseGet(() -> server.newSession(name));
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
