package io.github.libtmux.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.libtmux.LibTmuxException;
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
final class TmuxTransportExceptionTest {

    @Test
    void aFailureCarriesItsDispatchCertaintyAndCause() {
        IOException cause = new IOException("no such file");
        TmuxTransportException failure =
                new TmuxTransportException("could not start tmux", DispatchOutcome.NOT_DISPATCHED, cause);

        assertEquals(DispatchOutcome.NOT_DISPATCHED, failure.outcome());
        assertSame(cause, failure.getCause());
    }

    @Test
    void everyLibraryFailureIsUncheckedAndSharesOneRoot() {
        TmuxTransportException failure = new TmuxTransportException("timed out", DispatchOutcome.UNKNOWN, null);

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
        TmuxTransportException failure = new TmuxTransportException("timed out", DispatchOutcome.UNKNOWN, null);

        TmuxTransportException restored = roundTrip(failure);

        assertEquals(DispatchOutcome.UNKNOWN, restored.outcome());
        assertEquals("timed out", restored.getMessage());
    }

    private static TmuxTransportException roundTrip(TmuxTransportException failure) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(failure);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (TmuxTransportException) in.readObject();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
