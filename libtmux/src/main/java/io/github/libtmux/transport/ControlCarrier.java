package io.github.libtmux.transport;

import java.io.IOException;
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
}
