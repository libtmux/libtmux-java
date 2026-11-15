package io.github.libtmux.kotlin

import io.github.libtmux.Dimensions
import io.github.libtmux.PaneEdges
import io.github.libtmux.PaneId
import io.github.libtmux.PaneRun
import io.github.libtmux.PanePosition
import io.github.libtmux.TextOutcome
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
 * A tmux pane, with operations for input, output, and layout.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `PaneOperations.kt`. [Companion] hosts the generated query field namespace
 * (`Pane.command`, `Pane.active`, ...).
 *
 * A property or a plain function here reads the capture this handle came from and issues no command.
 * Every `suspend` function asks tmux now, dispatched through the [Server]'s policy, except the waits,
 * which run on their own dispatcher.
 *
 * # What is on here
 *
 * The members fall into a few groups:
 *
 * **Reading the capture.** [id], [index], [active], [currentCommand], [floating], [size], [position],
 * [edges], [title], [currentPath] and [pid] read what this capture stored about the pane, and
 * [window] and [server] are what it sits in. The id is stable and the index is state, since panes
 * renumber as neighbours come and go. [floating] is null when the running tmux cannot say, which
 * before 3.7 is always, and [pid] is null when tmux reported no process. [currentPath] throws when
 * this JVM cannot represent the directory's name.
 *
 * [id], [index], [active], [currentCommand], [floating], [size], [position], [edges], [title],
 * [currentPath], [pid], [window], [server] and [asJava]
 *
 * **Sending input.** [send][Pane.send] types keys without pressing Enter and
 * [sendLine][Pane.sendLine] presses it, which is how a command gets run. [sendKeys][Pane.sendKeys]
 * resolves each entry through tmux's key table and types an unknown name as text;
 * [sendLiteral][Pane.sendLiteral] types every entry as the characters it spells, so `C-c` is three
 * characters. [paste][Pane.paste] delivers text as one block through a private buffer that is
 * removed afterwards, and it throws for text containing NUL and before tmux 3.4.
 * [noteTyped][Pane.noteTyped] tells a later wait about text that reached the pane without passing
 * through this handle.
 *
 * [send][Pane.send], [sendLine][Pane.sendLine], [sendKeys][Pane.sendKeys],
 * [sendLiteral][Pane.sendLiteral], [paste][Pane.paste], [pasteBuffer][Pane.pasteBuffer] and
 * [noteTyped][Pane.noteTyped]
 *
 * **Reading output.** [capture][Pane.capture] returns the visible content, one element per line, read
 * from tmux now rather than from the capture. Give it a `CaptureSpec` to read part of the history; it
 * throws when the spec asks for something this server does not have. [pipeTo][Pane.pipeTo] sends
 * everything the pane prints to a shell command until [stopPiping][Pane.stopPiping], and
 * [clearHistory][Pane.clearHistory] discards the scrollback.
 *
 * [capture][Pane.capture], [pipeTo][Pane.pipeTo], [stopPiping][Pane.stopPiping] and
 * [clearHistory][Pane.clearHistory]
 *
 * **Running and waiting.** [run] runs a shell command to its end and answers with its exit status and
 * output, and throws when the pane is not running a POSIX shell. Reach for it first when the command
 * is yours. When it is not, [awaitText] and [await] poll the screen or the pane's state on a timer
 * and answer with why they ended; a pane killed mid-wait ends [awaitText] as `TextOutcome.PANE_GONE`
 * rather than an exception. Cancelling the coroutine interrupts a wait and is not a timeout.
 * [dead][Pane.dead] reads fresh whether the pane's process has exited, and throws when the pane is
 * gone from a server that still answers.
 *
 * [run], [awaitText], [await] and [dead][Pane.dead]
 *
 * **Splitting and arranging.** [split][Pane.split] divides this pane in half, or as a `SplitSpec`
 * describes, and returns the pane that appeared. [breakOut][Pane.breakOut] moves the pane into a
 * window of its own, [joinTo][Pane.joinTo] into another window, and [swapWith][Pane.swapWith] swaps
 * it with another pane. [resize][Pane.resize] grows it by cells in one direction,
 * [resizeTo][Pane.resizeTo] sets exact dimensions, and [select][Pane.select] makes it the active
 * pane. An unnamed [breakOut][Pane.breakOut] throws on exact tmux 3.7, which a named break or 3.7a
 * avoids.
 *
 * [split][Pane.split], [breakOut][Pane.breakOut], [joinTo][Pane.joinTo], [swapWith][Pane.swapWith],
 * [resize][Pane.resize], [resizeTo][Pane.resizeTo] and [select][Pane.select]
 *
 * **Modes.** [copyMode][Pane.copyMode] puts the pane into copy mode, [mode][Pane.mode] reads live
 * which mode it is in, null when it is showing its program, and [exitMode][Pane.exitMode] leaves
 * whichever mode it is in.
 *
 * [copyMode][Pane.copyMode], [mode][Pane.mode] and [exitMode][Pane.exitMode]
 *
 * **Changing the pane.** [retitle][Pane.retitle] returns a replacement capture, because this handle
 * keeps its original state. [respawn][Pane.respawn] kills what runs here and starts the pane's
 * default command or one you give, [respawnIn][Pane.respawnIn] does so in a directory, and
 * [kill][Pane.kill] closes the pane.
 *
 * [retitle][Pane.retitle], [respawn][Pane.respawn], [respawnIn][Pane.respawnIn] and
 * [kill][Pane.kill]
 *
 * **Options and hooks.** [options] and [hooks] reach what is set on this pane alone.
 *
 * [options] and [hooks]
 *
 * **Formats and refreshing.** [expand][Pane.expand] expands a tmux format, and
 * [variables][Pane.variables] reads several named variables, in this pane's context.
 * [refresh][Pane.refresh] takes a new capture to see the pane as it is now. It leaves this handle
 * unchanged and returns the new one, and throws if the pane is gone from a server that still
 * answers.
 *
 * [expand][Pane.expand], [variables][Pane.variables] and [refresh][Pane.refresh]
 */
public class Pane internal constructor(internal val java: JavaPane, public val server: Server) {

    /** The Java pane this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaPane get() = java

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
    public fun batch(): Batch = Batch(java.batch(), server)

    /** This pane's own hooks. */
    public fun hooks(): Hooks = Hooks(java.hooks(), server)

    /** This pane's own options. */
    public fun options(): Options = Options(java.options(), server)

    /**
     * Waits until [text] shows in this pane.
     *
     * Cancelling the coroutine interrupts the wait. That is not a timeout, and it does not stop a
     * program already running in the pane.
     *
     * @param timeout how long to keep looking, finite and not negative
     * @param every how long to leave between looks, at least 10 ms
     * @param dispatcher where the blocking wait runs
     * @return why the wait ended; a pane killed mid-wait is [TextOutcome.PANE_GONE], not an exception
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
