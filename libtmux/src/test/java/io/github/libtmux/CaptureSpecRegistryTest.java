package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.internal.support.Capabilities;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The capture flags are gated at the releases the registry names, not at releases of their own. */
final class CaptureSpecRegistryTest {

    private static void gated(String first, String lastRefused, Consumer<CaptureSpec.Builder> flag) {
        CaptureSpec.Builder builder = CaptureSpec.builder();
        flag.accept(builder);
        CaptureSpec spec = builder.build();
        assertDoesNotThrow(() -> spec.argv("%1", TmuxVersion.parse(first)), "allowed on " + first);
        assertThrows(
                UnsupportedFeatureException.class,
                () -> spec.argv("%1", TmuxVersion.parse(lastRefused)),
                "refused on " + lastRefused);
    }

    @Test
    void theModeScreenArrivedInTmux36() {
        gated("3.6", "3.5a", CaptureSpec.Builder::fromModeScreen);
        assertEquals("3.6", Capabilities.CAPTURE_MODE_SCREEN.since().toString());
    }

    @Test
    void hyperlinksAndLineNumbersArrivedInTmux37() {
        gated("3.7", "3.6b", CaptureSpec.Builder::withHyperlinks);
        gated("3.7", "3.6b", CaptureSpec.Builder::withLineNumbers);
        assertEquals("3.7", Capabilities.CAPTURE_LINE_FLAGS.since().toString());
    }
}
