package io.github.libtmux.kotlin

import io.github.libtmux.Keys as JavaKeys

/**
 * The server's key bindings, in one key table or across all of them.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `KeysOperations.kt`.
 */
public class Keys internal constructor(internal val java: JavaKeys, public val server: Server) {

    /** The Java keys this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaKeys get() = java

    /** The same bindings, in one named table. A pure, local view: it issues no command. */
    public fun `in`(table: String): Keys = Keys(java.`in`(table), server)

    override fun equals(other: Any?): Boolean = other is Keys && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
