package com.git_pull.libtmux;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Which tmux server to talk to.
 *
 * <p>A value, decided and comparable before any process runs. Two endpoints are equal when they name
 * the same server lexically; nothing here resolves symlinks, contacts the filesystem, or opens a
 * socket. That matters because an endpoint is routinely built, logged and compared while the socket
 * it names does not exist yet.
 *
 * <p>Validation that needs tmux — above all whether a socket path fits the platform's
 * {@code sun_path} — happens at dispatch, where the error can carry the command that hit it.
 */
public sealed interface ServerEndpoint {

    /** The flags that select this server, in argv order. */
    List<String> flags();

    /** tmux's own default server, wherever tmux decides that is. */
    static ServerEndpoint defaultSocket() {
        return new Default();
    }

    /** A server named with {@code -L}, resolved by tmux under its own socket directory. */
    static ServerEndpoint namedSocket(String name) {
        return new NamedSocket(name);
    }

    /** A server at an exact socket path, selected with {@code -S}. */
    static ServerEndpoint socketPath(Path path) {
        return new SocketPath(path);
    }

    /** Adds no flags, so tmux applies its own default socket resolution. */
    record Default() implements ServerEndpoint {
        @Override
        public List<String> flags() {
            return List.of();
        }
    }

    /**
     * A server tmux locates by name under its own socket directory.
     *
     * @param name a bare name, with no path separator
     */
    record NamedSocket(String name) implements ServerEndpoint {

        public NamedSocket {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("socket name is empty");
            }
            if (name.indexOf('/') >= 0) {
                // tmux resolves -L under its own socket directory, so a separator here cannot mean
                // what it looks like. Silently reinterpreting it would point at the wrong server.
                throw new IllegalArgumentException("socket name contains a path separator: " + name);
            }
        }

        @Override
        public List<String> flags() {
            return List.of("-L", name);
        }
    }

    /**
     * A server at an exact socket path.
     *
     * @param path the socket path, anchored and lexically normalized so equal sockets compare equal
     */
    record SocketPath(Path path) implements ServerEndpoint {

        public SocketPath {
            Objects.requireNonNull(path, "path");
            if (path.toString().isEmpty()) {
                throw new IllegalArgumentException("socket path is empty");
            }
            path = path.toAbsolutePath().normalize();
        }

        @Override
        public List<String> flags() {
            return List.of("-S", path.toString());
        }
    }
}
