package io.github.libtmux.kotlin

import io.github.libtmux.Dimensions
import io.github.libtmux.WindowId
import io.github.libtmux.WindowIndex
import io.github.libtmux.WindowLayout
import io.github.libtmux.snapshot.WindowContext
import io.github.libtmux.Window as JavaWindow

/**
 * A tmux window at a specific position in a session.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `WindowOperations.kt`. [Companion] hosts the generated query field namespace.
 *
 * A property or a plain function here reads the capture this handle came from and issues no command.
 * Every `suspend` extension asks tmux now, dispatched through the [Server]'s policy.
 *
 * # What is on here
 *
 * The members fall into a few groups:
 *
 * **Reading the capture.** [id], [index], [context], [name], [active], [linked], [size] and [layout]
 * read what this capture stored about the window, and [server] and [session] are the [Server] and
 * [Session] it sits in. The id names the underlying window and the index names this link, so a window
 * linked into two sessions has one id and two handles. A rename or a resize after the capture does
 * not show in it.
 *
 * [id], [index], [context], [name], [active], [linked], [size], [layout], [server], [session] and
 * [asJava]
 *
 * **Panes.** [panes] lists this link's panes in tmux's order and [activePane] is the one tmux had
 * active, null when the capture holds none; both are pure reads. [split][Window.split] divides the
 * active pane in half, or as a `SplitSpec` describes, and returns the pane that appeared. It throws
 * when the spec asks for something this server does not have.
 *
 * [panes], [activePane] and [split][Window.split]
 *
 * **Layout and size.** [selectLayout][Window.selectLayout] rearranges the panes into one of tmux's
 * built-in layouts, [nextLayout][Window.nextLayout] and [previousLayout][Window.previousLayout] step
 * to a neighbour, and [rotate][Window.rotate] rotates the panes. Read the arrangement from [layout]
 * and hand it back to [applyLayout][Window.applyLayout], which checks a string before tmux sees it.
 * [resizeTo][Window.resizeTo] takes terminal cells. [synchronizePanes][Window.synchronizePanes]
 * copies input typed into one pane into every pane of the window until
 * [stopSynchronizingPanes][Window.stopSynchronizingPanes].
 *
 * [selectLayout][Window.selectLayout], [nextLayout][Window.nextLayout],
 * [previousLayout][Window.previousLayout], [applyLayout][Window.applyLayout],
 * [rotate][Window.rotate], [resizeTo][Window.resizeTo],
 * [synchronizePanes][Window.synchronizePanes] and
 * [stopSynchronizingPanes][Window.stopSynchronizingPanes]
 *
 * **Moving and linking.** [select][Window.select] makes the window the active one in its session.
 * [linkTo][Window.linkTo] links it into another session so one window sits in both,
 * [moveTo][Window.moveTo] moves it to another session or to an exact index there, and
 * [unlink][Window.unlink] removes one link and throws when it is the window's only one, which tmux
 * refuses to remove.
 *
 * [select][Window.select], [linkTo][Window.linkTo], [moveTo][Window.moveTo] and
 * [unlink][Window.unlink]
 *
 * **Changing the window.** [rename][Window.rename] returns a replacement capture, because this handle
 * keeps its original state. [respawn][Window.respawn] kills what is running in the window and starts
 * it again, [displayPopup][Window.displayPopup] shows a popup over it and needs an attached client,
 * and [kill][Window.kill] closes it.
 *
 * [rename][Window.rename], [respawn][Window.respawn], [displayPopup][Window.displayPopup] and
 * [kill][Window.kill]
 *
 * **Options and hooks.** [options] and [hooks] reach what is set on this window alone, and every link
 * to the window shares them.
 *
 * [options] and [hooks]
 *
 * **Formats and refreshing.** [expand][Window.expand] expands a tmux format in this window's
 * context, and [refresh][Window.refresh] takes a new capture to see the window as it is now. It
 * leaves this handle unchanged and returns the new one, and throws if the window is no longer linked
 * here, from a server that still answers.
 *
 * [expand][Window.expand] and [refresh][Window.refresh]
 */
public class Window internal constructor(internal val java: JavaWindow, public val server: Server) {

    /** The Java window this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaWindow get() = java

    /** The underlying window, shared by every link to it. */
    public val id: WindowId get() = java.id()

    /** Where this link sits in its session. */
    public val index: WindowIndex get() = java.index()

    /** The winlink this handle addresses. */
    public val context: WindowContext get() = java.context()

    /** The window name. */
    public val name: String get() = java.name()

    /** Whether this was its session's active window when captured. */
    public val active: Boolean get() = java.active()

    /** Whether the underlying window is linked into more than one session. */
    public val linked: Boolean get() = java.linked()

    /** How large the window was when captured, in terminal cells. */
    public val size: Dimensions get() = java.size()

    /** tmux's own layout for this window, in whichever form this server writes it. */
    public val layout: WindowLayout get() = java.layout()

    /** The pane tmux had active here. A pure read of the capture. */
    public val activePane: Pane? get() = java.activePane().map { Pane(it, server) }.orElse(null)

    /** The session this link belongs to. A pure read of the capture. */
    public val session: Session get() = Session(java.session(), server)

    /** This link's panes, in tmux's order. A pure read of the capture. */
    public val panes: List<Pane> get() = java.panes().map { Pane(it, server) }

    /** This window's own hooks, which every link to it shares. */
    public fun hooks(): Hooks = Hooks(java.hooks(), server)

    /** This window's own options, which every link to it shares. */
    public fun options(): Options = Options(java.options(), server)

    override fun equals(other: Any?): Boolean = other is Window && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()

    public companion object
}
