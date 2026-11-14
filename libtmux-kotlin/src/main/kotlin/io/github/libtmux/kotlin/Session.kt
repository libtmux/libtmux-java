package io.github.libtmux.kotlin

import io.github.libtmux.SessionId
import io.github.libtmux.Session as JavaSession

/**
 * A tmux session and its captured windows and properties.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `SessionOperations.kt`. [Companion] hosts the generated query field namespace
 * (`Session.name`, `Session.attached`, ...).
 *
 * A property or a plain function here reads the capture this handle came from and issues no command.
 * Every `suspend` extension asks tmux now, dispatched through the [Server]'s policy.
 *
 * # What is on here
 *
 * The members fall into a few groups:
 *
 * **Reading the capture.** [id], [name] and [attached] read what this capture stored about the
 * session, and [server] is the [Server] it lives on. The id is stable, the name is state a user may
 * change at any time, and a rename or an attach after the capture does not show in it.
 *
 * [id], [name], [attached], [server] and [asJava]
 *
 * **Windows and panes.** [windows] lists the session's windows in tmux's order, and [activeWindow]
 * and [activePane] are the window tmux had active and the active pane of that window, each null when
 * the capture holds none. All three are pure reads and issue no command. [selectWindow][Session.selectWindow]
 * makes a window the active one, [nextWindow][Session.nextWindow] and
 * [previousWindow][Session.previousWindow] step to a neighbour with wrap-around, and
 * [lastWindow][Session.lastWindow] goes back to the one that was active before.
 *
 * [windows], [activeWindow], [activePane], [selectWindow][Session.selectWindow],
 * [nextWindow][Session.nextWindow], [previousWindow][Session.previousWindow] and
 * [lastWindow][Session.lastWindow]
 *
 * **Changing the session.** [newWindow][Session.newWindow] creates a window from a name or a
 * `WindowSpec` and returns the window tmux made. [rename][Session.rename] returns a replacement
 * capture, because this handle keeps its original state. [setHistoryLimit][Session.setHistoryLimit]
 * sets the scrollback retained by panes created in this session afterwards.
 * [detachClients][Session.detachClients] leaves the session running, and [kill][Session.kill] takes
 * every window with it.
 *
 * [newWindow][Session.newWindow], [rename][Session.rename], [setHistoryLimit][Session.setHistoryLimit],
 * [detachClients][Session.detachClients] and [kill][Session.kill]
 *
 * **Options, environment, and hooks.** These reach what is set on this session alone: its own
 * options, its own environment, which a process started in it is given on top of the server's, and
 * its own hooks. The [Server] holds the global ones, which a session inherits. Each returns a
 * wrapper whose own operations are `suspend`.
 *
 * [options], [environment] and [hooks]
 *
 * **Formats and refreshing.** [expand][Session.expand] expands a tmux format in this session's
 * context, and [refresh][Session.refresh] takes a new capture to see the session as it is now. It
 * leaves this handle unchanged and returns the new one, and throws if the session is gone from a
 * server that still answers.
 *
 * [expand][Session.expand] and [refresh][Session.refresh]
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
    public fun options(): Options = Options(java.options(), server)

    /** This session's own environment, which a process started in it is given on top of the server's. */
    public fun environment(): Environment = Environment(java.environment(), server)

    /** This session's own hooks. */
    public fun hooks(): Hooks = Hooks(java.hooks(), server)

    override fun equals(other: Any?): Boolean = other is Session && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()

    public companion object
}
