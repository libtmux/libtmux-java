package io.github.libtmux;

import java.util.List;
import java.util.Objects;
import kotlin.annotations.jvm.ReadOnly;

/**
 * How a wait for text on a pane ended, and what the pane had shown by then.
 *
 * <p>{@link Pane#awaitText} answers with the outcome alone. This carries the screen as well, which a
 * wait that ends because the pane or the server went away needs most: the output so far is all there
 * will ever be.
 *
 * @param outcome why the wait ended
 * @param output the rows of the pane at its last successful read, wrapped rows rejoined, the echo of
 *     what this library typed removed and trailing blank rows dropped; empty when the pane was gone
 *     before the first read
 */
public record TextWait(TextOutcome outcome, @ReadOnly List<String> output) {
    public TextWait {
        Objects.requireNonNull(outcome, "outcome");
        output = List.copyOf(output);
    }
}
