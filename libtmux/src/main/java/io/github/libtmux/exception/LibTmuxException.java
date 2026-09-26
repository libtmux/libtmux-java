package io.github.libtmux.exception;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import kotlin.annotations.jvm.ReadOnly;
import org.jspecify.annotations.Nullable;

/**
 * The root of every operational failure this library raises.
 *
 * <p>Unchecked, because a tmux failure can arise from any call and forcing every caller to declare
 * it would say nothing. Sealed, with every branch abstract, so matching on the permitted subtypes is
 * exhaustive in Java, Kotlin and Scala alike.
 *
 * <p>A failure that came from a tmux command carries that command, its exit status when it has one,
 * and the error lines tmux printed. Those lines, and the message that quotes them, name what tmux
 * named — sessions, windows, panes and buffers included.
 */
public abstract sealed class LibTmuxException extends RuntimeException
        permits CardinalityException,
                CommandRejectedException,
                ControlEndedException,
                DispatchException,
                MalformedResponseException,
                ServerUnavailableException,
                TargetGoneException,
                UnencodableTextException,
                UnsupportedFeatureException {

    private static final long serialVersionUID = 1L;

    private final @Nullable String command;
    private final int exitCode;
    private final String[] errorLines;

    LibTmuxException(String message, @Nullable Throwable cause) {
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

    /** The error lines tmux printed, in full. Empty when this failure has none. */
    @ReadOnly
    public List<String> errorLines() {
        return List.of(errorLines);
    }
}
