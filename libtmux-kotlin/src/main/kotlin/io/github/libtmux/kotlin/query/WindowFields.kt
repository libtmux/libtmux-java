package io.github.libtmux.kotlin.query

import io.github.libtmux.Window_
import io.github.libtmux.kotlin.Window
import io.github.libtmux.Pane as JavaPane
import io.github.libtmux.Session as JavaSession
import io.github.libtmux.Window as JavaWindow

/** Hand-mapped from `io.github.libtmux.Window_`; see `PaneFields.kt`'s header for why. */

/** The underlying window id, shared by every link to it. */
public val Window.Companion.id: TextField<JavaWindow> get() = TextField(Window_.id())

/** The window name. */
public val Window.Companion.name: TextField<JavaWindow> get() = TextField(Window_.name())

/** Where this link sits in its session. */
public val Window.Companion.index: NumberField<JavaWindow> get() = NumberField(Window_.index())

/** Whether this was its session's active window. */
public val Window.Companion.active: FlagField<JavaWindow> get() = FlagField(Window_.active())

/** Whether the underlying window is linked into more than one session. */
public val Window.Companion.linked: FlagField<JavaWindow> get() = FlagField(Window_.linked())

/** This link's panes. */
public val Window.Companion.panes: ToManyField<JavaWindow, JavaPane> get() = ToManyField(Window_.panes())

/** The session this link belongs to. */
public val Window.Companion.session: ToOneField<JavaWindow, JavaSession> get() = ToOneField(Window_.session())
