package io.github.libtmux.mcp;

import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The pane this server is itself running in, when it is running in one.
 *
 * <p>An MCP client launches this process, and often does so from inside tmux. That makes one pane
 * different from every other: typing into it types into the conversation, and killing it kills the
 * thing the model is talking through.
 *
 * <p>The socket and server process are checked as well as the pane, because a pane id is only unique
 * within one server. Destructive tools refuse when that relationship cannot be proved.
 */
final class Caller {

    private enum Relation {
        OUTSIDE,
        DIFFERENT_SERVER,
        SELF,
        UNKNOWN
    }

    private static final Caller NOWHERE = new Caller(Relation.OUTSIDE, null);
    private static final Caller DIFFERENT = new Caller(Relation.DIFFERENT_SERVER, null);
    private static final Caller UNKNOWN = new Caller(Relation.UNKNOWN, null);

    private final Relation relation;
    private final @Nullable Claim claim;

    private Caller(Relation relation, @Nullable Claim claim) {
        this.relation = relation;
        this.claim = claim;
    }

    /** Works out which pane, if any, on {@code server} is the one this process runs in. */
    static Caller of(Server server) {
        return of(server, System.getenv());
    }

    static Caller of(Server server, Map<String, String> environment) {
        boolean hasTmux = environment.containsKey("TMUX");
        boolean hasPane = environment.containsKey("TMUX_PANE");
        if (!hasTmux && !hasPane) {
            return NOWHERE;
        }
        if (!hasTmux || !hasPane) {
            return UNKNOWN;
        }
        Claim claim = Claim.parse(environment.get("TMUX"), environment.get("TMUX_PANE"));
        if (claim == null) {
            return UNKNOWN;
        }
        FileRelation socket = sameFile(claim.socket(), socketOf(server));
        if (socket == FileRelation.DIFFERENT) {
            return DIFFERENT;
        }
        if (socket == FileRelation.UNKNOWN) {
            return UNKNOWN;
        }
        Long serverPid = pidOf(server);
        if (serverPid == null || serverPid != claim.serverPid()) {
            return UNKNOWN;
        }
        return new Caller(Relation.SELF, claim);
    }

    void requireConsistent(long serverPid, String socket, Map<String, Set<String>> paneSessions) {
        if (relation != Relation.SELF) {
            return;
        }
        Claim selected = java.util.Objects.requireNonNull(claim);
        FileRelation socketRelation;
        try {
            socketRelation = sameFile(selected.socket(), Path.of(socket));
        } catch (RuntimeException failure) {
            socketRelation = FileRelation.UNKNOWN;
        }
        Set<String> sessions = paneSessions.get(selected.pane().value());
        if (serverPid != selected.serverPid()
                || socketRelation != FileRelation.SAME
                || sessions == null
                || !sessions.contains(selected.sessionId())) {
            throw new IllegalStateException("pane input refuses inconsistent caller identity");
        }
    }

    private enum FileRelation {
        SAME,
        DIFFERENT,
        UNKNOWN
    }

    private static @Nullable Long pidOf(Server server) {
        try {
            long pid = Long.parseLong(server.expand("#{pid}"));
            return pid > 0 ? pid : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** For a server that is known not to be the one this process runs in. */
    static Caller nowhere() {
        return NOWHERE;
    }

    /** The pane this process runs in, empty when it does not run in one on this server. */
    Optional<PaneId> pane() {
        return claim == null ? Optional.empty() : Optional.of(claim.pane());
    }

    /** Whether acting on {@code target} would act on the conversation itself. */
    boolean isSelf(PaneId target) {
        return claim != null && target.equals(claim.pane());
    }

    /** Whether the process is inside tmux but its relation to this server is unprovable. */
    boolean uncertain() {
        return relation == Relation.UNKNOWN;
    }

    /** tmux is asked which socket it is on, rather than the endpoint being reassembled from flags. */
    private static @Nullable Path socketOf(Server server) {
        try {
            String reported = server.expand("#{socket_path}");
            return reported.isEmpty() ? null : Path.of(reported);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Compared by what the filesystem says rather than by text, so a socket reached through a
     * symlink or a relative path is still the same socket.
     */
    private static FileRelation sameFile(Path left, @Nullable Path right) {
        if (right == null) {
            return FileRelation.UNKNOWN;
        }
        try {
            return left.toRealPath().equals(right.toRealPath()) ? FileRelation.SAME : FileRelation.DIFFERENT;
        } catch (IOException | RuntimeException e) {
            return FileRelation.UNKNOWN;
        }
    }

    private record Claim(Path socket, long serverPid, String sessionId, PaneId pane) {

        private static @Nullable Claim parse(@Nullable String tmux, @Nullable String pane) {
            if (tmux == null || tmux.isEmpty() || pane == null || pane.isEmpty()) {
                return null;
            }
            int lastComma = tmux.lastIndexOf(',');
            int firstOfPair = tmux.lastIndexOf(',', lastComma - 1);
            if (lastComma < 0 || firstOfPair < 0) {
                return null;
            }
            String socket = tmux.substring(0, firstOfPair);
            String pid = tmux.substring(firstOfPair + 1, lastComma);
            String session = tmux.substring(lastComma + 1);
            try {
                RouteValue.requireSafe(socket, "caller tmux socket path");
                Path socketPath = Path.of(socket);
                long parsedPid = Long.parseLong(pid);
                long parsedSession = Long.parseLong(session);
                long parsedPane = Long.parseLong(pane.substring(1));
                if (!socketPath.isAbsolute()
                        || parsedPid <= 0
                        || !pid.equals(Long.toString(parsedPid))
                        || parsedSession < 0
                        || !session.equals(Long.toString(parsedSession))
                        || parsedPane < 0
                        || parsedPane > 4_294_967_295L
                        || !pane.equals("%" + parsedPane)) {
                    return null;
                }
                return new Claim(socketPath, parsedPid, "$" + session, new PaneId(pane));
            } catch (RuntimeException failure) {
                return null;
            }
        }
    }
}
