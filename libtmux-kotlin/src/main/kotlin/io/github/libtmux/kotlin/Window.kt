package io.github.libtmux.kotlin

import io.github.libtmux.Dimensions
import io.github.libtmux.WindowIndex
import io.github.libtmux.WindowLayout
import io.github.libtmux.snapshot.WindowContext
import io.github.libtmux.Window as JavaWindow

/**
 * One tmux window at one of its positions, as one capture saw it.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `WindowOperations.kt`. [Companion] hosts the generated query field namespace.
 */
public class Window internal constructor(internal val java: JavaWindow, public val server: Server) {

    /** The underlying window, shared by every link to it. */
    public val id: io.github.libtmux.WindowId get() = java.id()

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

    override fun equals(other: Any?): Boolean = other is Window && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()

    public companion object
}
