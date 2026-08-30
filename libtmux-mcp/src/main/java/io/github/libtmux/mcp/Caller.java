package io.github.libtmux.mcp;

import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.TmuxEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
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
    private final @Nullable PaneId pane;

    private Caller(Relation relation, @Nullable PaneId pane) {
        this.relation = relation;
        this.pane = pane;
    }

    /** Works out which pane, if any, on {@code server} is the one this process runs in. */
    static Caller of(Server server) {
        return of(server, System.getenv());
    }

    static Caller of(Server server, Map<String, String> environment) {
        String raw = environment.get("TMUX");
        if (raw == null || raw.isEmpty()) {
            return NOWHERE;
        }
        Optional<TmuxEnvironment> inside = TmuxEnvironment.of(environment);
        if (inside.isEmpty()) {
            return UNKNOWN;
        }
        TmuxEnvironment here = inside.get();
        Optional<PaneId> pane = here.pane();
        if (pane.isEmpty()) {
            return UNKNOWN;
        }
        Long serverPid = pidOf(server);
        if (serverPid == null) {
            return UNKNOWN;
        }
        if (serverPid != here.serverPid()) {
            return DIFFERENT;
        }
        return switch (sameFile(here.socket(), socketOf(server))) {
            case SAME -> new Caller(Relation.SELF, pane.get());
            case DIFFERENT -> DIFFERENT;
            case UNKNOWN -> UNKNOWN;
        };
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
        return Optional.ofNullable(pane);
    }

    /** Whether acting on {@code target} would act on the conversation itself. */
    boolean isSelf(PaneId target) {
        return target.equals(pane);
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
            return left.toRealPath().equals(right.toRealPath())
                    ? FileRelation.SAME
                    : FileRelation.DIFFERENT;
        } catch (IOException | RuntimeException e) {
            return FileRelation.UNKNOWN;
        }
    }
}
