package io.github.libtmux.kotlin

import io.github.libtmux.Environment as JavaEnvironment

/**
 * The environment tmux gives to processes it starts, at one scope.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `EnvironmentOperations.kt`.
 */
public class Environment internal constructor(internal val java: JavaEnvironment, public val server: Server) {

    /** The Java environment this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaEnvironment get() = java

    override fun equals(other: Any?): Boolean = other is Environment && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
