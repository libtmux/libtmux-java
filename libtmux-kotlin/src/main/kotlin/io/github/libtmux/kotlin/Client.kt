package io.github.libtmux.kotlin

import io.github.libtmux.ClientAttachment
import io.github.libtmux.Client as JavaClient

/**
 * One attached tmux client, as one capture saw it.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `ClientOperations.kt`. [Companion] hosts the generated query field namespace.
 */
public class Client internal constructor(internal val java: JavaClient, public val server: Server) {

    /** The client's terminal name, which is how tmux addresses it. */
    public val name: String get() = java.name()

    /** The session this client was attached to when captured. A pure read of the capture. */
    public val session: Session? get() = java.session().map { Session(it, server) }.orElse(null)

    /** What this client was looking at when captured. A pure read: it issues no command. */
    public val attachment: ClientAttachment? get() = java.attachment().orElse(null)

    override fun equals(other: Any?): Boolean = other is Client && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()

    public companion object
}
