package io.github.libtmux;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * The root of every operational failure this library raises.
 *
 * <p>Unchecked, because a tmux failure can arise from any call and forcing every caller to declare
 * it would say nothing. This covers transport, tmux, hydration and query failures. It does not cover
 * programmer error: a null argument is a {@link NullPointerException}, an invalid value an
 * {@link IllegalArgumentException}, and use after close an {@link IllegalStateException}.
 *
 * <p>A failure that came from a tmux command carries that command, its exit status when it has one,
 * and the error lines tmux printed. The message is the same sentence as before.
 */
public class LibTmuxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final @Nullable String command;
    private final int exitCode;
    private final String[] errorLines;

    public LibTmuxException(String message) {
        this(message, null);
    }

    public LibTmuxException(String message, @Nullable Throwable cause) {
        this(message, cause, null, -1, List.of());
    }

    LibTmuxException(
            String message,
            @Nullable Throwable cause,
            @Nullable String command,
            int exitCode,
            List<String> errorLines) {
        super(message, cause);
        this.command = command;
        this.exitCode = exitCode;
        this.errorLines = errorLines.toArray(String[]::new);
    }

    /** The command that failed, when this failure came from one. */
    public Optional<String> command() {
        return Optional.ofNullable(command);
    }

    /** The process exit status, when the failure has one. */
    public OptionalInt exitCode() {
        return exitCode < 0 ? OptionalInt.empty() : OptionalInt.of(exitCode);
    }

    /** The error lines tmux printed. Empty when this failure has none. */
    public List<String> errorLines() {
        return List.of(errorLines);
    }
}
