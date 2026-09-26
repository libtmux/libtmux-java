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
 * tmux always gives a new session one window; the first [window] block configures that one (by
 * [Window.rename], since a `new-session` cannot be told the first window's directory separately from
 * the session's own), and each later block is a genuine [Session.newWindow] — so `window { }` twice
 * means exactly two windows, not three.
 */
public suspend fun Server.newSession(configure: SessionBuilder.() -> Unit): Session {
    val builder = SessionBuilder().apply(configure)
    var session = newSession(builder.build())
    val windowBlocks = builder.windows.iterator()
    if (windowBlocks.hasNext()) {
        val first = WindowBuilder().apply(windowBlocks.next())
        val firstWindow = session.activeWindow ?: error("the session just created has no active window")
        applySplits(if (first.name != null) firstWindow.rename(first.name!!) else firstWindow, first)
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
    private val java: SessionSpec.Builder = SessionSpec.builder()
    internal val windows: MutableList<WindowBuilder.() -> Unit> = mutableListOf()

    public var name: String?
        get() = null
        set(value) {
            value?.let { java.named(it) }
        }

    public var directory: Path?
        get() = null
        set(value) {
            value?.let { java.`in`(it) }
        }

    public fun window(configure: WindowBuilder.() -> Unit) {
        windows += configure
    }

    internal fun build(): SessionSpec = java.build()
}

@LibTmuxDsl
public class WindowBuilder internal constructor() {
    private val java: WindowSpec.Builder = WindowSpec.builder()
    internal val splits: MutableList<SplitBuilder.() -> Unit> = mutableListOf()

    /** Read back (unlike the other builders' write-only properties) so the first window can rename. */
    public var name: String? = null
        set(value) {
            field = value
            value?.let { java.named(it) }
        }

    public var directory: Path?
        get() = null
        set(value) {
            value?.let { java.`in`(it) }
        }

    public fun split(configure: SplitBuilder.() -> Unit = {}) {
        splits += configure
    }

    internal fun build(): WindowSpec = java.build()
}

@LibTmuxDsl
public class SplitBuilder internal constructor() {
    private val java: SplitSpec.Builder = SplitSpec.builder()

    public var directory: Path?
        get() = null
        set(value) {
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
