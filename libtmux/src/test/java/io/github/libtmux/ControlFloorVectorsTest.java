package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.internal.ControlAcquisition;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Control needs tmux 3.7: its own development and candidate builds do not qualify, later ones do. */
final class ControlFloorVectorsTest {

    @Test
    void theControlFloorFollowsTheReleaseTrain() {
        List<String> refused = List.of("3.6b", "next-3.7", "3.7-rc", "3.7-rc2", "3.7-rc9", "3.3a", "next-3.6");
        List<String> supported =
                List.of("3.7", "3.7a", "3.7d", "3.8-rc", "3.8-rc2", "next-3.8", "next-3.9", "3.10", "4.0");
        for (String version : refused) {
            assertEquals(false, ControlAcquisition.supports(TmuxVersion.parse(version)), version);
            assertThrows(
                    UnsupportedFeatureException.class,
                    () -> ControlAcquisition.requireSupported(TmuxVersion.parse(version)),
                    version);
        }
        for (String version : supported) {
            assertEquals(true, ControlAcquisition.supports(TmuxVersion.parse(version)), version);
            ControlAcquisition.requireSupported(TmuxVersion.parse(version));
        }
    }
}
