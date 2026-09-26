package io.github.libtmux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalLong;

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
    private final long processId;
    // -1 when not known; tmux reports seconds since the epoch.
    private final long startTime;

    private ServerIdentity(String realm, String server, long processId, long startTime) {
        this.realm = realm;
        this.server = server;
        this.processId = processId;
        this.startTime = startTime;
    }

    static ServerIdentity of(String realm, ServerEndpoint endpoint) {
        return new ServerIdentity(Objects.requireNonNull(realm, "realm"), digest(endpoint), 0, -1);
    }

    /** This server as one process: a pid alone can be reused, so its start time is part of it. */
    ServerIdentity at(long pid, OptionalLong startTime) {
        if (pid < 1) {
            throw new IllegalArgumentException("pid is not positive: " + pid);
        }
        return new ServerIdentity(realm, server, pid, startTime.orElse(-1));
    }

    /** The execution realm the transport reaches tmux through. */
    public String realm() {
        return realm;
    }

    /** A stable opaque key for the server, carrying no socket path. */
    public String server() {
        return server;
    }

    /** The live tmux process, present on an identity bound to a captured handle. */
    public OptionalLong processId() {
        return processId == 0 ? OptionalLong.empty() : OptionalLong.of(processId);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ServerIdentity that
                && realm.equals(that.realm)
                && server.equals(that.server)
                && processId == that.processId
                && startTime == that.startTime;
    }

    @Override
    public int hashCode() {
        return Objects.hash(realm, server, processId, startTime);
    }

    @Override
    public String toString() {
        return "ServerIdentity[" + realm + ":" + server + (processId == 0 ? "" : "@" + processId)
                + (startTime < 0 ? "" : "+" + startTime) + "]";
    }

    private static String digest(ServerEndpoint endpoint) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\0", endpoint.flags()).getBytes(StandardCharsets.UTF_8));
            // 128 bits: equal keys mean one server, and a shorter digest invites a collision.
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
