package io.github.libtmux.transport;

import io.github.libtmux.internal.CommandStrings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One tmux invocation: how to reach the server, then the commands to run there.
 *
 * <p>Commands are held literally. tmux ends a command at an argument whose last byte is {@code ;},
 * so a value ending in one would otherwise be truncated with no error; a transport encodes for the
 * parser it feeds, and {@link #commands()} answers with what the caller meant.
 *
 * <p>The timeout rides on the request instead of the transport because the same server serves both
 * a listing that should answer immediately and an attach that never returns.
 *
 * @param endpoint the tmux executable and its server selection, such as {@code [tmux, -S, path]}.
 *     tmux reads these before it parses commands, so they are passed through untouched
 * @param commands one or more tmux commands, each an argv of literal words. tmux runs them in
 *     order and discards the rest after the first failure
 * @param timeout how long the caller will wait for the whole invocation
 * @param input what the commands read from tmux's standard input, empty for the commands that
 *     read none. A tmux argument is bounded by MAX_IMSGSIZE, so text too large to be one
 *     travels here instead.
 */
public record CommandRequest(List<String> endpoint, List<List<String>> commands, Duration timeout, String input) {

    public CommandRequest {
        endpoint = List.copyOf(endpoint);
        commands = commands.stream().map(List::copyOf).toList();
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(input, "input");
        if (endpoint.isEmpty()) {
            throw new IllegalArgumentException("endpoint has no executable");
        }
        if (commands.isEmpty() || commands.stream().anyMatch(List::isEmpty)) {
            throw new IllegalArgumentException("a command has no words");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout is not positive");
        }
    }

    /** A request for one command, which is every request but a batch. */
    public static CommandRequest of(List<String> endpoint, List<String> argv, Duration timeout) {
        return of(endpoint, argv, timeout, "");
    }

    /** A request for one command that reads {@code input} from tmux's standard input. */
    public static CommandRequest of(List<String> endpoint, List<String> argv, Duration timeout, String input) {
        return new CommandRequest(endpoint, List.of(argv), timeout, input);
    }

    /** The full argv to hand a process builder, encoded for tmux's own argv parser. */
    public List<String> commandLine() {
        List<String> encoded = CommandStrings.arguments(commands);
        List<String> line = new ArrayList<>(endpoint.size() + encoded.size());
        line.addAll(endpoint);
        line.addAll(encoded);
        return Collections.unmodifiableList(line);
    }

    /**
     * Counts only. Commands carry pane content and socket paths, and this value reaches log lines
     * and failed assertions.
     */
    @Override
    public String toString() {
        return "CommandRequest[commandCount=" + commands.size() + ", timeout=" + timeout + "]";
    }
}
