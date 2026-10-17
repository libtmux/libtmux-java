package io.github.libtmux.transport;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Starts a long-lived tmux process in a transport's realm.
 *
 * <p>A control client stays attached, so it cannot borrow a command permit. It still has to be
 * started by the same carrier as the commands, or a custom transport's realm is silently ignored
 * and the client attaches to whatever the local machine calls tmux.
 */
@FunctionalInterface
public interface ControlCarrier {

    /**
     * Starts the process. The caller owns it.
     *
     * @param command the tmux invocation, arguments already separate
     * @return the running process
     * @throws IOException if the process cannot be started
     */
    Process start(List<String> command) throws IOException;

    /**
     * Retains this realm's Unix socket against replacement before control initialization.
     *
     * <p>The alias must refer to the same socket inode for its lifetime, within a private directory
     * on the same filesystem. Replacing the original pathname must never redirect the alias.
     * The caller owns the returned pin; the carrier remains borrowed.
     *
     * @param socketPath the daemon's own socket path in this carrier's realm
     * @param timeout the remaining acquisition budget
     * @return the retained endpoint
     * @throws IOException if pinning fails
     * @throws UnsupportedOperationException when this carrier cannot retain an endpoint
     */
    default PinnedSocket pinSocket(String socketPath, Duration timeout) throws IOException {
        throw new UnsupportedOperationException("this carrier cannot retain a tmux socket");
    }

    /** A retained socket alias owned by one control client. */
    interface PinnedSocket extends AutoCloseable {
        /** The alias pathname in the carrier's realm. */
        String socketPath();

        /** Removes only the alias and directory created by this pin. */
        @Override
        void close() throws IOException;
    }
}
