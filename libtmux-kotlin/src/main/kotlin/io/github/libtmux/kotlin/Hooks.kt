package io.github.libtmux.kotlin

import io.github.libtmux.Hooks as JavaHooks

/**
 * Read and update hooks for the server, session, window, or pane that owns this handle.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `HooksOperations.kt`.
 */
public class Hooks internal constructor(internal val java: JavaHooks, public val server: Server) {

    /** The Java hooks this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaHooks get() = java

    override fun equals(other: Any?): Boolean = other is Hooks && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
