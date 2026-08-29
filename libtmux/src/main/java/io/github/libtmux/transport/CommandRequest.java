package io.github.libtmux.transport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One tmux invocation: how to reach the server, then the command to run there.
 *
 * <p>An argv list, never a shell string, so a semicolon or a space inside an argument is data
 * rather than syntax. The timeout rides on the request instead of the transport because the same
 * server serves both a listing that should answer immediately and an attach that never returns.
 *
 * @param endpoint the tmux executable and its server selection, such as {@code [tmux, -S, path]}
 * @param argv the command and its arguments, each already a separate element
 * @param timeout how long the caller will wait for the whole invocation
 * @param input what the command reads from tmux's standard input, empty for the commands that
 *     read none. A tmux argument is bounded by MAX_IMSGSIZE, so text too large to be one
 *     travels here instead.
 */
public record CommandRequest(List<String> endpoint, List<String> argv, Duration timeout, String input) {

    public CommandRequest {
        endpoint = List.copyOf(endpoint);
        argv = List.copyOf(argv);
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(input, "input");
        if (endpoint.isEmpty()) {
            throw new IllegalArgumentException("endpoint has no executable");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout is not positive");
        }
    }

    /** A request for a command that reads nothing, which is every command but a buffer load. */
    public CommandRequest(List<String> endpoint, List<String> argv, Duration timeout) {
        this(endpoint, argv, timeout, "");
    }

    /** The full argv to hand a process builder. */
    public List<String> commandLine() {
        List<String> line = new ArrayList<>(endpoint.size() + argv.size());
        line.addAll(endpoint);
        line.addAll(argv);
        return Collections.unmodifiableList(line);
    }

    /**
     * Counts only. argv carries pane content and socket paths, and this value reaches log lines and
     * failed assertions.
     */
    @Override
    public String toString() {
        return "CommandRequest[argumentCount=" + argv.size() + ", timeout=" + timeout + "]";
    }
}
