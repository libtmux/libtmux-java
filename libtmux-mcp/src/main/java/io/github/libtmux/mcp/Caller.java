package io.github.libtmux.mcp;

import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.TmuxEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
 * <p>The socket is checked as well as the pane, because a pane id is only unique within one server
 * and this process may have been pointed at a different one. Unprovable means not the caller's: a
 * wrong "yes" disarms a guard, while a wrong "no" only declines to help.
 */
final class Caller {

    private static final Caller NOWHERE = new Caller(null);

    private final @Nullable PaneId pane;

    private Caller(@Nullable PaneId pane) {
        this.pane = pane;
    }

    /** Works out which pane, if any, on {@code server} is the one this process runs in. */
    static Caller of(Server server) {
        return of(server, System.getenv());
    }

    static Caller of(Server server, Map<String, String> environment) {
        Optional<TmuxEnvironment> inside = TmuxEnvironment.of(environment);
        if (inside.isEmpty()) {
            return NOWHERE;
        }
        TmuxEnvironment here = inside.get();
        Optional<PaneId> pane = here.pane();
        if (pane.isEmpty() || !sameFile(here.socket(), socketOf(server))) {
            return NOWHERE;
        }
        return new Caller(pane.get());
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

    /** tmux is asked which socket it is on, rather than the endpoint being reassembled from flags. */
    private static @Nullable Path socketOf(Server server) {
        try {
            List<String> reported =
                    server.cmd("display-message", "-p", "#{socket_path}").stdout();
            return reported.isEmpty() ? null : Path.of(reported.get(0));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Compared by what the filesystem says rather than by text, so a socket reached through a
     * symlink or a relative path is still the same socket.
     */
    private static boolean sameFile(Path left, @Nullable Path right) {
        if (right == null) {
            return false;
        }
        try {
            return left.toRealPath().equals(right.toRealPath());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
