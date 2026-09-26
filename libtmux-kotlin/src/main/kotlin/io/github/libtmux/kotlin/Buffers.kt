package io.github.libtmux.kotlin

import io.github.libtmux.Buffers as JavaBuffers

/**
 * The tmux server's paste buffers.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `BuffersOperations.kt`.
 */
public class Buffers internal constructor(internal val java: JavaBuffers, public val server: Server) {

    /** The Java buffers this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaBuffers get() = java

    override fun equals(other: Any?): Boolean = other is Buffers && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
