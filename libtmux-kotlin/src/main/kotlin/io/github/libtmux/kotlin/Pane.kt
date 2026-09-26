package io.github.libtmux.kotlin

import io.github.libtmux.Dimensions
import io.github.libtmux.Hooks
import io.github.libtmux.Options
import io.github.libtmux.PaneEdges
import io.github.libtmux.PaneId
import io.github.libtmux.PaneRun
import io.github.libtmux.PanePosition
import io.github.libtmux.TextOutcome
import io.github.libtmux.batch.Batch
import io.github.libtmux.WakeReason
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import io.github.libtmux.Pane as JavaPane

/**
 * One tmux pane, as one capture saw it.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `PaneOperations.kt`. [Companion] hosts the generated query field namespace
 * (`Pane.command`, `Pane.active`, ...).
 */
public class Pane internal constructor(internal val java: JavaPane, public val server: Server) {

    /** The pane's stable id. */
    public val id: PaneId get() = java.id()

    /** The pane's position, which shifts as neighbours come and go. */
    public val index: Int get() = java.index()

    /** Whether this was its window's active pane when captured. */
    public val active: Boolean get() = java.active()

    /** The command tmux reported running here. */
    public val currentCommand: String get() = java.currentCommand()

    /** Whether this pane floats, or null when the running tmux cannot say. */
    public val floating: Boolean? get() = java.floating().orElse(null)

    /** How large the pane was when captured, in terminal cells. */
    public val size: Dimensions get() = java.size()

    /** Where the pane's top-left corner sat in its window when captured, in terminal cells. */
    public val position: PanePosition get() = java.position()

    /** The pane title, which a program running inside it can change. */
    public val title: String get() = java.title()

    /** The working directory tmux reported for the pane. */
    public val currentPath: Path get() = java.currentPath()

    /** The process id of the program running in the pane, captured at the time of the read. */
    public val pid: Long? get() = java.pid().let { if (it.isPresent) it.asLong else null }

    /** Which sides of its window the pane touches. */
    public val edges: PaneEdges get() = java.edges()

    /** The window link this pane was reached through. A pure read of the capture. */
    public val window: Window get() = Window(java.window(), server)

    /** Collects commands fenced to the server incarnation that produced this pane. */
    public fun batch(): Batch = java.batch()

    /** This pane's own hooks. */
    public fun hooks(): Hooks = java.hooks()

    /** This pane's own options. */
    public fun options(): Options = java.options()

    /**
     * Waits until [text] shows in this pane.
     *
     * Cancelling the coroutine interrupts the wait. That is not a timeout, and it does not stop a
     * program already running in the pane.
     *
     * @param timeout how long to keep looking, finite and not negative
     * @param every how long to leave between looks, at least 10 ms
     * @param dispatcher where the blocking wait runs
     * @return why the wait ended
     */
    public suspend fun awaitText(
        text: String,
        timeout: Duration,
        every: Duration = 50.milliseconds,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): TextOutcome =
        runInterruptible(dispatcher) { java.awaitText(text, timeout.toJavaDuration(), every.toJavaDuration()) }

    /**
     * Waits until [settled] accepts this pane, read fresh from tmux before each test.
     *
     * Cancelling the coroutine interrupts the wait. That is not a timeout. A read already sent to
     * tmux is not taken back.
     *
     * @param timeout how long to keep looking, finite and not negative
     * @param every how long to leave between looks, at least 10 ms
     * @param dispatcher where the blocking wait runs
     * @return why the wait ended
     */
    public suspend fun await(
        timeout: Duration,
        every: Duration = 50.milliseconds,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        settled: (Pane) -> Boolean,
    ): WakeReason =
        runInterruptible(dispatcher) {
            java.await({ fresh -> settled(Pane(fresh, server)) }, timeout.toJavaDuration(), every.toJavaDuration())
        }

    /**
     * Runs a shell command in this pane to its end, and answers with its exit status and output.
     *
     * Cancelling the coroutine interrupts the wait. The command keeps running in the pane; only the
     * wait for its end is what cancellation reaches.
     *
     * @param command a line of shell, run as `eval` would run it
     * @param timeout how long to wait for it to end
     * @param dispatcher where the blocking wait runs
     * @return the command's exit status and what it printed
     */
    public suspend fun run(
        command: String,
        timeout: Duration,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): PaneRun = runInterruptible(dispatcher) { java.run(command, timeout.toJavaDuration()) }

    override fun equals(other: Any?): Boolean = other is Pane && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()

    public companion object
}
