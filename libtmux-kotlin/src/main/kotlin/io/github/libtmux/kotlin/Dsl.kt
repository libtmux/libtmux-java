package io.github.libtmux.kotlin

import io.github.libtmux.SessionSpec
import io.github.libtmux.SplitSpec
import io.github.libtmux.WindowSpec
import java.nio.file.Path

/**
 * Marks every builder below so an inner block cannot reach an outer block's receiver by accident:
 * `split { directory = x }` inside `window { }` resolves to the innermost [SplitBuilder], never to
 * the [WindowBuilder] it is nested in. Reaching the outer receiver on purpose still works, spelled
 * explicitly (`this@window.directory = x`).
 */
@DslMarker
public annotation class LibTmuxDsl

/**
 * Declares a session's shape, then creates it: one session, its windows, and each window's splits,
 * as a single call lowering to the right sequence of suspend operations.
 *
 * tmux always gives a new session one window, and `new-session` itself names it, gives it its
 * directory and starts its command. So the first [window] block goes into that one command rather
 * than a rename afterwards, which could not move a shell already running; each later block is a
 * genuine [Session.newWindow], and `window { }` twice means exactly two windows, not three.
 *
 * @throws IllegalArgumentException if the session and its first window block both set a
 *     directory, or both set a command: tmux has one of each for that window
 */
public suspend fun Server.newSession(configure: SessionBuilder.() -> Unit): Session {
    val builder = SessionBuilder().apply(configure)
    val windowBlocks = builder.windows.iterator()
    val first = if (windowBlocks.hasNext()) WindowBuilder().apply(windowBlocks.next()) else null
    var session = newSession(builder.build(first))
    if (first != null) {
        val firstWindow = session.activeWindow ?: error("the session just created has no active window")
        applySplits(firstWindow, first)
    }
    for (windowBlock in windowBlocks) {
        val windowBuilder = WindowBuilder().apply(windowBlock)
        applySplits(session.newWindow(windowBuilder.build()), windowBuilder)
    }
    // The session handle returned by newSession(), and the one held since, are both the capture from
    // before any window/split was added — including the first window's own splits, which change its
    // pane count without changing the window count that would otherwise hint a refresh is needed — so
    // refresh whenever at least one window block ran, not only when a later block added a window.
    if (builder.windows.isNotEmpty()) {
        session = session.refresh()
    }
    return session
}

private suspend fun applySplits(window: Window, builder: WindowBuilder) {
    for (splitBlock in builder.splits) {
        val splitBuilder = SplitBuilder().apply(splitBlock)
        window.split(splitBuilder.build())
    }
}

@LibTmuxDsl
public class SessionBuilder internal constructor() {
    internal val windows: MutableList<WindowBuilder.() -> Unit> = mutableListOf()
    private var command: List<String>? = null

    public var name: String? = null

    /** The session's working directory, which its first window starts in too. */
    public var directory: Path? = null

    /** Runs [argv] in the first window instead of the default shell. */
    public fun running(vararg argv: String) {
        command = argv.toList()
    }

    public fun window(configure: WindowBuilder.() -> Unit) {
        windows += configure
    }

    internal fun build(first: WindowBuilder?): SessionSpec {
        val spec = SessionSpec.builder()
        name?.let { spec.named(it) }
        first?.name?.let { spec.firstWindowNamed(it) }
        require(directory == null || first?.directory == null || directory == first.directory) {
            "the session and its first window block both set a directory; tmux starts that window in one"
        }
        (first?.directory ?: directory)?.let { spec.`in`(it) }
        require(command == null || first?.command == null) {
            "the session and its first window block both set a command; that window runs one"
        }
        (first?.command ?: command)?.let { spec.running(*it.toTypedArray()) }
        return spec.build()
    }
}

@LibTmuxDsl
public class WindowBuilder internal constructor() {
    internal val splits: MutableList<SplitBuilder.() -> Unit> = mutableListOf()
    internal var command: List<String>? = null
        private set

    public var name: String? = null

    public var directory: Path? = null

    /** Runs [argv] in this window instead of the default shell. */
    public fun running(vararg argv: String) {
        command = argv.toList()
    }

    public fun split(configure: SplitBuilder.() -> Unit = {}) {
        splits += configure
    }

    internal fun build(): WindowSpec {
        val spec = WindowSpec.builder()
        name?.let { spec.named(it) }
        directory?.let { spec.`in`(it) }
        command?.let { spec.running(*it.toTypedArray()) }
        return spec.build()
    }
}

@LibTmuxDsl
public class SplitBuilder internal constructor() {
    private val java: SplitSpec.Builder = SplitSpec.builder()

    public var directory: Path? = null
        set(value) {
            field = value
            value?.let { java.`in`(it) }
        }

    /** Puts the new pane below this one. tmux's own default if no direction is chosen. */
    public fun below() {
        java.below()
    }

    /** Puts the new pane above this one. */
    public fun above() {
        java.above()
    }

    /** Puts the new pane to the right of this one. */
    public fun toRight() {
        java.toRight()
    }

    /** Puts the new pane to the left of this one. */
    public fun toLeft() {
        java.toLeft()
    }

    /** Sizes the new pane by an exact number of cells. */
    public fun cells(count: Int) {
        java.cells(count)
    }

    /** Sizes the new pane as a percentage of the space being split. */
    public fun percent(share: Int) {
        java.percent(share)
    }

    internal fun build(): SplitSpec = java.build()
}
