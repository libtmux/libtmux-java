package io.github.libtmux.transport;

/**
 * Receives one {@link OperationReport} after each command.
 *
 * <p>The transport still throws on failure. This does not retry. A report whose {@link
 * OperationReport#certainty() certainty} is {@link DispatchOutcome#NOT_DISPATCHED} was never
 * applied. {@link DispatchOutcome#UNKNOWN} must not be sent again blindly.
 */
@FunctionalInterface
public interface OperationObserver {

    /** Does nothing. */
    OperationObserver NONE = report -> {};

    /**
     * Called once, after the command ends, on the thread that ran it.
     *
     * <p>An exception thrown here does not change the command's result, since the command has
     * already run: failing it would invite a retry of something tmux may have done. The exception is
     * logged at {@code WARNING}, with its stack trace, by the logger of the transport or control
     * client that ran the command.
     */
    void accept(OperationReport report);
}
