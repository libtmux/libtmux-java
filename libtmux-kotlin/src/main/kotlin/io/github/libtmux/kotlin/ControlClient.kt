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
     *
     * Output tmux sends before the subscription opens is not delivered, and a cold flow opens it
     * only when collection starts. So a command whose output is wanted belongs in [onSubscribed],
     * which runs once the subscription exists and before the first read, rather than before
     * `collect`: `control.output(32) { control.send("send-keys", ...) }`.
     */
    public fun output(capacity: Int, onSubscribed: suspend () -> Unit = {}): Flow<Delivery<PaneOutput>> =
        coldFlowFrom({ java.subscribeOutput(capacity) }, onSubscribed)

    /** State changes tmux volunteers, as a cold [Flow]. Reads, and takes [onSubscribed], as [output] does. */
    public fun events(capacity: Int, onSubscribed: suspend () -> Unit = {}): Flow<Delivery<ControlEvent>> =
        coldFlowFrom({ java.subscribeEvents(capacity) }, onSubscribed)

    /** Ends the client, rejecting queued requests and resolving picked requests as uncertain. */
    override fun close() {
        java.close()
    }
}
