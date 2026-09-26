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

    override fun equals(other: Any?): Boolean = other is CommandChain && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
