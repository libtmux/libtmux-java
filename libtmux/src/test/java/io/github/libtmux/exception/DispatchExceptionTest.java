package io.github.libtmux.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.Idempotence;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * A transport failure has to say how certain it is that tmux applied the command, because
 * "it timed out" and "it never started" call for opposite recovery.
 */
final class DispatchExceptionTest {

    @Test
    void aFailureCarriesItsDispatchCertaintyAndCause() {
        IOException cause = new IOException("no such file");
        DispatchException failure =
                new DispatchException.Failed("could not start tmux", DispatchOutcome.NOT_DISPATCHED, cause);

        assertEquals(DispatchOutcome.NOT_DISPATCHED, failure.outcome());
        assertSame(cause, failure.getCause());
    }

    @Test
    void everyLibraryFailureIsUncheckedAndSharesOneRoot() {
        DispatchException failure = new DispatchException.Failed("timed out", DispatchOutcome.UNKNOWN, null);

        assertInstanceOf(LibTmuxException.class, failure);
        assertInstanceOf(RuntimeException.class, failure, "callers are not forced to declare tmux failures");
    }

    /**
     * Exceptions cross process and logging boundaries, and an outcome that did not survive the
     * crossing would read as {@code NOT_DISPATCHED} to a null-checking caller. The outcome is an
     * enum, so nothing about it needs to be dropped.
     */
    @Test
    void theOutcomeSurvivesSerialization() {
        DispatchException failure =
                new DispatchException.TimedOut("timed out", DispatchOutcome.UNKNOWN, Idempotence.IDEMPOTENT, null);

        DispatchException restored = roundTrip(failure);

        assertEquals(DispatchOutcome.UNKNOWN, restored.outcome());
        assertEquals(Idempotence.IDEMPOTENT, restored.idempotence());
        assertEquals(true, restored.safeToRetry());
        assertEquals("timed out", restored.getMessage());
    }

    /** A failure that does not know what it sent assumes the worst: a change, never a read. */
    @Test
    void aFailureWithoutItsRequestIsNotSafeToResendOnceDispatched() {
        assertEquals(false, new DispatchException.Failed("broke", DispatchOutcome.UNKNOWN, null).safeToRetry());
        assertEquals(
                true, new DispatchException.Failed("never ran", DispatchOutcome.NOT_DISPATCHED, null).safeToRetry());
    }

    private static DispatchException roundTrip(DispatchException failure) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(failure);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (DispatchException) in.readObject();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
