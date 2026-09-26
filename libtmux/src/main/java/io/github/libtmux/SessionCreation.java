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
     * <p>{@code new-session} may be the first command said to a fresh socket, where {@link
     * Server#version()} fails for want of a daemon. So this asks a running daemon when one answers,
     * since it may be a different build than the configured binary, and otherwise asks the binary
     * through {@link #binaryVersion}.
     *
     * <p>Reads {@link SnapshotCapture#processForCreation()} rather than {@link Server#version()}: a
     * daemon too old to talk to fails the identity probe as one never started does ("server exited
     * unexpectedly"), and {@link ServerUnavailableException} does not keep that distinction.
     * Falling back to {@code binaryVersion} for such a daemon would report the binary's version as
     * the daemon's.
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
     * endpoint nothing is listening on yet, with or without a {@code -f} pointed at a config file
     * that does not exist.
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
