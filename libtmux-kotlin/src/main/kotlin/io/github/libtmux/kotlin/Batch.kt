package io.github.libtmux.kotlin

import io.github.libtmux.batch.Batch as JavaBatch

/**
 * Several tmux commands collected to run together in one invocation.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `BatchOperations.kt`.
 */
public class Batch internal constructor(internal val java: JavaBatch, public val server: Server) {

    /** The Java batch this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaBatch get() = java

    /** How many operations have been collected. A pure, local read. */
    public fun size(): Int = java.size()

    /**
     * How many bytes the collected operations come to as the one command tmux parses. A pure, local
     * read.
     */
    public fun length(): Int = java.length()

    override fun equals(other: Any?): Boolean = other is Batch && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
