package io.github.libtmux.kotlin

import io.github.libtmux.Shell as JavaShell

/**
 * A shell command run by tmux, and a tmux command chosen by a shell command's exit status.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `ShellOperations.kt`.
 */
public class Shell internal constructor(internal val java: JavaShell, public val server: Server) {

    /** The Java shell this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaShell get() = java

    override fun equals(other: Any?): Boolean = other is Shell && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
