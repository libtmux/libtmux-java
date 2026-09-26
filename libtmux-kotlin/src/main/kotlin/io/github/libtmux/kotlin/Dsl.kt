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
 * as a single call lowering to the right sequence of suspend operations — [Server.newSession], then
 * [Session.newWindow] per [window] block, then [Window.split] per nested [SplitBuilder.split] block.
 */
public suspend fun Server.newSession(configure: SessionBuilder.() -> Unit): Session {
    val builder = SessionBuilder().apply(configure)
    val session = newSession(builder.build())
    for (windowBlock in builder.windows) {
        val windowBuilder = WindowBuilder().apply(windowBlock)
        val window = session.newWindow(windowBuilder.build())
        for (splitBlock in windowBuilder.splits) {
            val splitBuilder = SplitBuilder().apply(splitBlock)
            window.split(splitBuilder.build())
        }
    }
    return session
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
    public fun below(): Unit = java.below().let {}

    /** Puts the new pane above this one. */
    public fun above(): Unit = java.above().let {}

    /** Puts the new pane to the right of this one. */
    public fun toRight(): Unit = java.toRight().let {}

    /** Puts the new pane to the left of this one. */
    public fun toLeft(): Unit = java.toLeft().let {}

    /** Sizes the new pane by an exact number of cells. */
    public fun cells(count: Int): Unit = java.cells(count).let {}

    /** Sizes the new pane as a percentage of the space being split. */
    public fun percent(share: Int): Unit = java.percent(share).let {}

    internal fun build(): SplitSpec = java.build()
}
