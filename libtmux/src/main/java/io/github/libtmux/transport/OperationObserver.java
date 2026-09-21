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

    /** Called once, after the command ends, on the thread that ran it. */
    void accept(OperationReport report);
}
