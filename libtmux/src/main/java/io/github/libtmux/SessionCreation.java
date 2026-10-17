package io.github.libtmux;

import io.github.libtmux.transport.CommandResult;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
