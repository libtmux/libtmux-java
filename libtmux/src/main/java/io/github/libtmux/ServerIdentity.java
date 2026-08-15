package io.github.libtmux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Which tmux server a handle belongs to.
 *
 * <p>Two entities with the same id are the same entity only if they came from the same server. The
 * realm exists because socket text alone is not enough: two unrelated execution realms — a local
 * process and a container, say — can both hold a server at {@code /tmp/s}, and entities from them
 * must not compare equal.
 *
 * <p>The server is identified by a digest of its endpoint rather than by the endpoint itself, so an
 * identity can be logged, compared and embedded in a message without carrying a socket path.
 */
public final class ServerIdentity {

    private final String realm;
    private final String server;

    private ServerIdentity(String realm, String server) {
        this.realm = realm;
        this.server = server;
    }

    static ServerIdentity of(String realm, ServerEndpoint endpoint) {
        return new ServerIdentity(Objects.requireNonNull(realm, "realm"), digest(endpoint));
    }

    /** The execution realm the transport reaches tmux through. */
    public String realm() {
        return realm;
    }

    /** A stable opaque key for the server, carrying no socket path. */
    public String server() {
        return server;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ServerIdentity that && realm.equals(that.realm) && server.equals(that.server);
    }

    @Override
    public int hashCode() {
        return Objects.hash(realm, server);
    }

    @Override
    public String toString() {
        return "ServerIdentity[" + realm + ":" + server + "]";
    }

    private static String digest(ServerEndpoint endpoint) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\0", endpoint.flags()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
