package io.github.libtmux.kotlin

import io.github.libtmux.Server as JavaServer
import io.github.libtmux.internal.ControlAcquisition
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Managed control needs tmux 3.7 or newer. A test that attaches a control client skips below it;
 * the integration suite asserts the refusal there.
 */
internal fun requireControl(server: JavaServer) {
    assumeTrue(ControlAcquisition.supports(server.version()), "managed control needs tmux 3.7 or newer")
}
