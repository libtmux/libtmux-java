package io.github.libtmux.kotlin

import io.github.libtmux.SessionId
import io.github.libtmux.Session as JavaSession

/**
 * One tmux session, as one capture saw it.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `SessionOperations.kt`. [Companion] hosts the generated query field namespace
 * (`Session.name`, `Session.attached`, ...).
 */
public class Session internal constructor(internal val java: JavaSession, public val server: Server) {

    /** The session's stable id. */
    public val id: SessionId get() = java.id()

    /** The session name, which a user may change at any time. */
    public val name: String get() = java.name()

    /** Whether a client was attached when this was captured. */
    public val attached: Boolean get() = java.attached()

    /** The window tmux had active in this session. A pure read of the capture. */
    public val activeWindow: Window? get() = java.activeWindow().map { Window(it, server) }.orElse(null)

    /** The active pane of the active window. A pure read of the capture. */
    public val activePane: Pane? get() = java.activePane().map { Pane(it, server) }.orElse(null)

    /** This session's windows, in tmux's order. A pure read of the capture. */
    public val windows: List<Window> get() = java.windows().map { Window(it, server) }

    override fun equals(other: Any?): Boolean = other is Session && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()

    public companion object
}
