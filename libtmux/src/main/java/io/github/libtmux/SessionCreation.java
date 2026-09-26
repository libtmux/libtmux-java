package io.github.libtmux;

import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.exception.MalformedResponseException;
import io.github.libtmux.exception.ServerUnavailableException;
import io.github.libtmux.internal.ErrorText;
import io.github.libtmux.transport.CommandResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Assembles the pieces {@link Server#newSession(SessionSpec)} needs before tmux has a session yet. */
final class SessionCreation {

    private SessionCreation() {}

    /**
     * tmux exits 0 even when nothing was created: an explicit {@code -S} under a directory that does
     * not exist prints its own {@code error creating ...} and still returns success, and a binary
     * that is not tmux at all can exit 0 with nothing on stdout the same way {@code run} cannot tell
     * apart from a real session id. Reads tmux's own words when it spoke and names the configured
     * binary when it did not, rather than letting an empty list reach {@code SessionId} and throw
     * an unchecked collection exception with neither in it.
     *
     * <p>tmux 3.2a says nothing at all when it cannot create its socket: the server queues the
     * error and tells the client to exit at once, and the client leaves before the error reaches
     * it. So a silent exit checks the one cause this client can see for itself.
     */
    static String failureMessage(ServerConfig config, CommandResult result) {
        if (!result.stderr().isEmpty()) {
            return "tmux new-session failed: " + String.join("; ", result.stderr());
        }
        if (config.endpoint() instanceof ServerEndpoint.SocketPath(Path socket)) {
            Path directory = socket.toAbsolutePath().getParent();
            if (directory != null && !Files.isDirectory(directory)) {
                return "tmux created no session: the socket's directory " + directory + " does not exist";
            }
        }
        return config.binary() + " exited 0 and reported no session id; is it tmux?";
    }

    /**
     * The version to gate a session-creation spec against.
     *
     * <p>{@code new-session} is the one command that may be the first thing said to a fresh socket,
     * so asking {@link Server#version()} — which needs an already-answering daemon — fails exactly
     * when a spec depends on the answer and nothing has started the daemon yet. Preferring the
     * running daemon when there is one keeps {@link Server#version()}'s own reasoning: a server
     * already up may have been started by a different build than this client is invoking. Only when
     * nothing answers at all does this fall back to {@link #binaryVersion}, which asks the executable
     * directly and needs no server.
     *
     * <p>Reads {@link SnapshotCapture#processForCreation()} directly rather than going through
     * {@link Server#version()}: a daemon this client is too old to talk to fails the identity probe
     * the same way one that was never started does ("server exited unexpectedly", confirmed against
     * the matrix), and {@link Server#version()}'s own {@link ServerUnavailableException} does not keep
     * that distinction once raised. Falling back to {@code binaryVersion} for that case would report
     * the configured binary's own version as though it were the daemon's — exactly the guarantee this
     * method exists to keep.
     */
    static TmuxVersion versionForCreation(Server server) {
        return server.capture()
                .processForCreation()
                .map(SnapshotCapture.ServerProcess::version)
                .orElseGet(() -> binaryVersion(server));
    }

    /**
     * Which tmux the configured binary is, without asking any server.
     *
     * <p>{@code -V} is handled before tmux touches a socket at all, so it answers even against an
     * endpoint nothing is listening on yet — confirmed against every matrix release, with or without
     * a {@code -f} pointed at a config file that does not exist.
     *
     * @throws LibTmuxException if the binary could not be run or did not report a version
     */
    private static TmuxVersion binaryVersion(Server server) {
        CommandResult result = server.cmd(List.of("-V"));
        if (!result.succeeded() || result.stdout().isEmpty()) {
            throw new MalformedResponseException(
                    "tmux -V did not report a version" + ErrorText.suffix(result.stderr()));
        }
        String reported = result.stdout().get(0);
        int space = reported.indexOf(' ');
        if (space < 0) {
            throw new MalformedResponseException("tmux -V reported an unexpected line: " + reported);
        }
        return TmuxVersion.parse(reported.substring(space + 1));
    }
}
