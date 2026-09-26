package io.github.libtmux;

import io.github.libtmux.catalog.Kind;
import io.github.libtmux.catalog.Operation;
import io.github.libtmux.exception.LibTmuxException;
import java.util.List;
import kotlin.annotations.jvm.ReadOnly;

/**
 * The server's own message log, newest last.
 *
 * <p>tmux before 3.6 answers {@code no current client} when nothing is attached. A gate would
 * refuse the case that works: with a client attached the log is readable on every supported
 * release. The failure tmux reports is left to reach the caller.
 */
public final class MessageLog {

    private final Server server;

    MessageLog(Server server) {
        this.server = server;
    }

    /**
     * The log lines.
     *
     * @throws LibTmuxException before 3.6 when no client is attached
     */
    @ReadOnly
    @Operation(Kind.READ)
    public List<String> lines() {
        return server.run(List.of("show-messages")).stdout();
    }
}
