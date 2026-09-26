package io.github.libtmux.kotlin

import io.github.libtmux.Environment
import io.github.libtmux.Hooks
import io.github.libtmux.Options
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

    /** The Java session this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaSession get() = java

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

    /** This session's own options. */
    public fun options(): Options = java.options()

    /** This session's own environment, which a process started in it is given on top of the server's. */
    public fun environment(): Environment = java.environment()

    /** This session's own hooks. */
    public fun hooks(): Hooks = java.hooks()

    override fun equals(other: Any?): Boolean = other is Session && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()

    public companion object
}
