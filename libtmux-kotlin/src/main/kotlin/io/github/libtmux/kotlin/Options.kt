package io.github.libtmux.kotlin

import io.github.libtmux.OptionKey
import kotlinx.coroutines.runInterruptible
import io.github.libtmux.Options as JavaOptions

/**
 * Read and update options for the server, session, window, or pane that owns this handle.
 *
 * Holds the Java handle privately, so a member here always wins over a same-named catalog-generated
 * extension in `OptionsOperations.kt`. `get`/`set` are hand-written in both overloads: a Kotlin member
 * makes every same-named extension unreachable regardless of its own parameter types, so the
 * `OptionKey<T>` overload cannot be added beside a generated `get(String)`/`set(String, String)`
 * without also hand-writing those.
 */
public class Options internal constructor(internal val java: JavaOptions, public val server: Server) {

    /** The Java options this wraps: the same object, for a Java API that takes one. */
    public val asJava: JavaOptions get() = java

    /**
     * The value in effect at this scope, inherited from a parent scope when this one does not set it.
     *
     * @return empty only when tmux does not know the option
     */
    public suspend fun get(name: String): String? =
        runInterruptible(server.policy.commands) { java.get(name) }.orElse(null)

    /** As [get], read as the key's type. */
    public suspend fun <T : Any> get(key: OptionKey<T>): T? =
        runInterruptible(server.policy.commands) { java.get(key) }.orElse(null)

    /** Sets one option at this scope. */
    public suspend fun set(name: String, value: String) {
        runInterruptible(server.policy.commands) { java.set(name, value) }
    }

    /** As [set], written the way tmux reads the key's type. */
    public suspend fun <T : Any> set(key: OptionKey<T>, value: T) {
        runInterruptible(server.policy.commands) { java.set(key, value) }
    }

    override fun equals(other: Any?): Boolean = other is Options && java == other.java

    override fun hashCode(): Int = java.hashCode()

    override fun toString(): String = java.toString()
}
