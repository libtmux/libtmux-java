package io.github.libtmux.kotlin

import io.github.libtmux.control.ControlEvent
import io.github.libtmux.control.Delivery
import io.github.libtmux.control.PaneOutput
import kotlinx.coroutines.flow.Flow
import io.github.libtmux.control.ControlClient as JavaControlClient

/**
 * A tmux client that stays attached and answers one command at a time.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `ControlClientOperations.kt`.
 */
public class ControlClient internal constructor(
    internal val java: JavaControlClient,
    public val server: Server,
) : AutoCloseable {

    /** Whether the client is still running. */
    public val isAlive: Boolean get() = java.isAlive

    /** The error text captured from this control process, at most 4096 bytes. */
    public val standardError: String get() = java.standardError()

    /** Whether [standardError] stopped before the process finished writing it. */
    public val standardErrorTruncated: Boolean get() = java.standardErrorTruncated()

    /**
     * Terminal output tmux pushes, as a cold [Flow].
     *
     * A fresh Java subscription opens per [kotlinx.coroutines.flow.Flow.collect] and closes when
     * collection ends, cancels, or throws — fan-out means collecting twice, each with its own gap
     * accounting. Not `suspend`: opening the subscription is local and does no tmux round trip: only
     * collecting it reads.
     */
    public fun output(capacity: Int): Flow<Delivery<PaneOutput>> = coldFlowFrom { java.subscribeOutput(capacity) }

    /** State changes tmux volunteers, as a cold [Flow]. Reads as [output] does. */
    public fun events(capacity: Int): Flow<Delivery<ControlEvent>> = coldFlowFrom { java.subscribeEvents(capacity) }

    /** Ends the client, rejecting queued requests and resolving picked requests as uncertain. */
    override fun close() {
        java.close()
    }
}
