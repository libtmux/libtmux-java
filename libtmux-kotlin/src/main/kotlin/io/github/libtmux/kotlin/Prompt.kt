package io.github.libtmux.kotlin

import io.github.libtmux.Prompt as JavaPrompt

/**
 * The server's command-prompt history.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `PromptOperations.kt`.
 */
public class Prompt internal constructor(internal val java: JavaPrompt, public val server: Server) {

    /** The Java prompt this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaPrompt get() = java

    override fun equals(other: Any?): Boolean = other is Prompt && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
