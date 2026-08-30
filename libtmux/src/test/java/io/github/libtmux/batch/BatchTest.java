package io.github.libtmux.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class BatchTest {

    @Test
    void lengthCountsUtf8Bytes() {
        Batch ascii = new Batch(commands -> {
                    throw new AssertionError("length must not dispatch");
                })
                .add("display-message", "-p", "e");
        Batch accented = new Batch(commands -> {
                    throw new AssertionError("length must not dispatch");
                })
                .add("display-message", "-p", "é");

        assertEquals(ascii.length() + 1, accented.length());
    }
}
