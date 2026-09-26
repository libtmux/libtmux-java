package io.github.libtmux.kotlin

import io.github.libtmux.MessageLog as JavaMessageLog

/**
 * The server's own message log, newest last.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `MessageLogOperations.kt`.
 */
public class MessageLog internal constructor(internal val java: JavaMessageLog, public val server: Server) {

    /** The Java message log this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaMessageLog get() = java

    override fun equals(other: Any?): Boolean = other is MessageLog && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
