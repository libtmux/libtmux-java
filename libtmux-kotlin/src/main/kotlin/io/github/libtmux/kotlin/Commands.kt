package io.github.libtmux.kotlin

import io.github.libtmux.Commands as JavaCommands

/**
 * The commands this tmux knows, read without starting a server to answer.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `CommandsOperations.kt`.
 */
public class Commands internal constructor(internal val java: JavaCommands, public val server: Server) {

    /** The Java commands this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaCommands get() = java

    override fun equals(other: Any?): Boolean = other is Commands && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
