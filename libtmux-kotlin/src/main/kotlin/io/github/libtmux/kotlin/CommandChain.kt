package io.github.libtmux.kotlin

import io.github.libtmux.CommandChain as JavaCommandChain

/**
 * A sequence of tmux commands where each one acts on what the last one made.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `CommandChainOperations.kt`.
 */
public class CommandChain internal constructor(internal val java: JavaCommandChain, public val server: Server) {

    /** The Java chain this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaCommandChain get() = java

    /** Makes a window named [name], which the commands after it act on. Collected, not sent. */
    public fun newWindow(name: String): CommandChain = apply { java.newWindow(name) }

    /** Renames the window the chain is on. Collected, not sent. */
    public fun renameWindow(name: String): CommandChain = apply { java.renameWindow(name) }

    /** Splits the current pane into left and right. Collected, not sent. */
    public fun splitLeftRight(): CommandChain = apply { java.splitLeftRight() }

    /** Splits the current pane into top and bottom. Collected, not sent. */
    public fun splitTopBottom(): CommandChain = apply { java.splitTopBottom() }

    /** Types [command] into the current pane and presses Enter. Collected, not sent. */
    public fun sendLine(command: String): CommandChain = apply { java.sendLine(command) }

    /** Adds any tmux command. Collected, not sent. */
    public fun then(vararg argv: String): CommandChain = apply { java.then(*argv) }

    /** Adds any tmux command. Collected, not sent. */
    public fun then(argv: List<String>): CommandChain = apply { java.then(argv) }

    override fun equals(other: Any?): Boolean = other is CommandChain && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
