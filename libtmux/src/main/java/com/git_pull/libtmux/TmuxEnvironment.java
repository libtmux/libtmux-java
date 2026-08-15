package com.git_pull.libtmux;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What tmux tells a program running inside one of its panes.
 *
 * <p>tmux exports {@code TMUX} as {@code <socket>,<server-pid>,<session>} and {@code TMUX_PANE} as
 * the pane's id. That is how a process discovers the server it is already inside, without being told
 * where to look.
 *
 * <pre>{@code
 * TmuxEnvironment here = TmuxEnvironment.current().orElseThrow();
 *
 * try (Server server = Server.open(here.config())) {
 *     Session mine = server.sessions().stream()
 *             .filter(session -> session.id().equals(here.session()))
 *             .findFirst()
 *             .orElseThrow();
 * }
 * }</pre>
 *
 * <p>Reading is separated from the environment itself, so the parsing is testable without a process
 * ever having to be started inside tmux: {@link #of} takes the variables, {@link #current} is the
 * one-line wrapper that fetches them.
 *
 * <p>A class rather than a record for the reason {@link ServerConfig} gives — and because the pane
 * is genuinely absent sometimes, which an {@link Optional} accessor says better than a nullable
 * record component.
 */
public final class TmuxEnvironment {

    private final Path socket;
    private final long serverPid;
    private final SessionId session;
    private final @Nullable PaneId pane;

    private TmuxEnvironment(Path socket, long serverPid, SessionId session, @Nullable PaneId pane) {
        this.socket = socket;
        this.serverPid = serverPid;
        this.session = session;
        this.pane = pane;
    }

    /**
     * Reads this process's own environment.
     *
     * @return what tmux said, or empty when this is not running inside tmux
     */
    public static Optional<TmuxEnvironment> current() {
        return of(System.getenv());
    }

    /**
     * Reads a set of environment variables.
     *
     * @param environment the variables to read, typically {@code System.getenv()}
     * @return what tmux said, or empty when {@code TMUX} is absent or unreadable
     */
    public static Optional<TmuxEnvironment> of(Map<String, String> environment) {
        String tmux = environment.get("TMUX");
        if (tmux == null || tmux.isEmpty()) {
            return Optional.empty();
        }
        // socket,pid,session — and a socket path may itself contain commas, so the two numeric
        // fields are taken from the end rather than the path from the start.
        int lastComma = tmux.lastIndexOf(',');
        int firstOfPair = tmux.lastIndexOf(',', lastComma - 1);
        if (lastComma < 0 || firstOfPair < 0) {
            return Optional.empty();
        }
        long pid;
        try {
            pid = Long.parseLong(tmux.substring(firstOfPair + 1, lastComma));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String sessionField = tmux.substring(lastComma + 1);
        if (sessionField.isEmpty()) {
            return Optional.empty();
        }
        // tmux writes the session number bare, while every id elsewhere carries its sigil. Without
        // this the id would never equal one read back from a listing.
        SessionId session = new SessionId(sessionField.startsWith("$") ? sessionField : "$" + sessionField);
        String paneField = environment.get("TMUX_PANE");
        PaneId pane = paneField == null || paneField.isEmpty() ? null : new PaneId(paneField);
        return Optional.of(new TmuxEnvironment(Path.of(tmux.substring(0, firstOfPair)), pid, session, pane));
    }

    /** The socket the server is listening on. */
    public Path socket() {
        return socket;
    }

    /** The process id of the tmux server, which is not the pane's process. */
    public long serverPid() {
        return serverPid;
    }

    /** The session this process is running in. */
    public SessionId session() {
        return session;
    }

    /** The pane this process is running in, absent when {@code TMUX_PANE} was not set. */
    public Optional<PaneId> pane() {
        return Optional.ofNullable(pane);
    }

    /** A config addressing this server, ready for {@link Server#open}. */
    public ServerConfig config() {
        return ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();
    }

    @Override
    public String toString() {
        return "TmuxEnvironment[" + socket + " " + session + (pane == null ? "" : " " + pane) + "]";
    }
}
