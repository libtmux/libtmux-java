package io.github.libtmux.kotlin.query

import io.github.libtmux.Session_
import io.github.libtmux.kotlin.Session
import io.github.libtmux.Session as JavaSession
import io.github.libtmux.Window as JavaWindow

/** Hand-mapped from `io.github.libtmux.Session_`; see `PaneFields.kt`'s header for why. */

/** The session id, as text, so it can be compared and listed. */
public val Session.Companion.id: TextField<JavaSession> get() = TextField(Session_.id())

/** The session name. */
public val Session.Companion.name: TextField<JavaSession> get() = TextField(Session_.name())

/** Whether a client was attached when this was captured. */
public val Session.Companion.attached: FlagField<JavaSession> get() = FlagField(Session_.attached())

/** This session's windows. */
public val Session.Companion.windows: ToManyField<JavaSession, JavaWindow> get() = ToManyField(Session_.windows())
