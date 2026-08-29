package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CleanupTest {

    @Test
    void everyCleanupRunsAndLaterFailuresAreSuppressed() {
        List<String> attempted = new ArrayList<>();
        IllegalStateException first = new IllegalStateException("first failed");
        IllegalArgumentException second = new IllegalArgumentException("second failed");
        Cleanup cleanup = new Cleanup();

        cleanup.close(() -> {
            attempted.add("first");
            throw first;
        });
        cleanup.run(() -> attempted.add("middle"));
        cleanup.close(() -> {
            attempted.add("second");
            throw second;
        });

        RuntimeException thrown = assertThrows(RuntimeException.class, cleanup::throwIfFailed);
        assertSame(first, thrown);
        assertEquals(List.of("first", "middle", "second"), attempted);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(second, thrown.getSuppressed()[0]);
    }
}
