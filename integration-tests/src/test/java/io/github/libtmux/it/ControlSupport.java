package io.github.libtmux.it;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.libtmux.Server;
import io.github.libtmux.internal.ControlAcquisition;

/**
 * Managed control needs tmux 3.7 or newer. A suite that attaches a control client skips below it;
 * {@link ControlFloorIntegrationTest} asserts the refusal there.
 */
final class ControlSupport {
    private ControlSupport() {}

    static void require(Server server) {
        assumeTrue(ControlAcquisition.supports(server.version()), "managed control needs tmux 3.7 or newer");
    }
}
