package io.github.libtmux.kotlin

import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerIdentity
import io.github.libtmux.WindowId
import io.github.libtmux.exception.CardinalityException
import io.github.libtmux.query.FilterExpr
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.runInterruptible
import io.github.libtmux.Pane as JavaPane
import io.github.libtmux.Server as JavaServer
import io.github.libtmux.Session as JavaSession
import io.github.libtmux.Window as JavaWindow

/**
 * One tmux server, reached over one transport.
 *
 * Holds the Java handle privately: this is a wrapper, not [JavaServer] itself, so a member here
 * always wins over a same-named catalog-generated extension (see `ServerOperations.kt`) and a query
 * field lives on [Companion] rather than colliding with an instance member.
 *
 * Every operation that may contact tmux is `suspend`, dispatched through [policy]. Close it once,
 * after the last call on any thread — the same threading contract [JavaServer] documents.
 */
public class Server private constructor(
    internal val java: JavaServer,
    public val policy: ExecutionPolicy,
) : AutoCloseable {

    /** How this server was configured. */
    public val config: ServerConfig get() = java.config()

    /** Which server this is. Every handle taken from it is scoped by this. */
    public val identity: ServerIdentity get() = java.identity()

    /** How many tmux commands this server's transport runs at once, from [ExecutionPolicy.default]. */
    public val admissionBound: Int get() = java.admissionBound()

    /**
     * The one session this expression matches, captured now.
     *
     * @throws CardinalityException.NoMatch if nothing matched
     * @throws CardinalityException.MultipleMatches if more than one session matched
     */
    public suspend fun session(expression: FilterExpr<JavaSession>): Session =
        sessionOrNull(expression)
            ?: throw CardinalityException.NoMatch("expected exactly one session, found none")

    /** The one session this expression matches, or null when none does. */
    public suspend fun sessionOrNull(expression: FilterExpr<JavaSession>): Session? =
        runInterruptible(policy.commands) {
            java.session(expression).map { Session(it, this) }.orElse(null)
        }

    /**
     * The one window link this expression matches, captured now.
     *
     * @throws CardinalityException.NoMatch if nothing matched
     * @throws CardinalityException.MultipleMatches if more than one link matched
     */
    public suspend fun window(expression: FilterExpr<JavaWindow>): Window =
        windowOrNull(expression)
            ?: throw CardinalityException.NoMatch("expected exactly one window, found none")

    /** The one window link this expression matches, or null when none does. */
    public suspend fun windowOrNull(expression: FilterExpr<JavaWindow>): Window? =
        runInterruptible(policy.commands) {
            java.window(expression).map { Window(it, this) }.orElse(null)
        }

    /**
     * The one pane this expression matches, captured now.
     *
     * @throws CardinalityException.NoMatch if nothing matched
     * @throws CardinalityException.MultipleMatches if more than one pane matched
     */
    public suspend fun pane(expression: FilterExpr<JavaPane>): Pane =
        paneOrNull(expression) ?: throw CardinalityException.NoMatch("expected exactly one pane, found none")

    /** The one pane this expression matches, or null when none does. */
    public suspend fun paneOrNull(expression: FilterExpr<JavaPane>): Pane? =
        runInterruptible(policy.commands) {
            java.pane(expression).map { Pane(it, this) }.orElse(null)
        }

    /** Every winlink of the window with this id, captured now. */
    public suspend fun windows(id: WindowId): List<Window> =
        runInterruptible(policy.commands) { java.windows(id).map { Window(it, this) } }

    /**
     * Attaches a control client to this session's server, and only to the process this capture
     * named.
     *
     * @param timeout how long to wait for the client to become ready
     */
    public suspend fun control(
        session: Session,
        timeout: Duration = config.defaultTimeout().toKotlinDuration(),
    ): ControlClient =
        runInterruptible(policy.commands) {
            ControlClient(java.control(session.java, timeout.toJavaDuration()), this)
        }

    /** Releases an owned transport. Idempotent, and never kills tmux. */
    override fun close() {
        java.close()
    }

    public companion object {

        /** A server over a transport it owns and closes. */
        public suspend fun open(
            config: ServerConfig,
            policy: ExecutionPolicy = ExecutionPolicy.default(config),
        ): Server = runInterruptible(policy.commands) { Server(JavaServer.open(config), policy) }
    }
}
