package io.github.libtmux.kotlin

import io.github.libtmux.Buffers
import io.github.libtmux.Channel
import io.github.libtmux.CommandChain
import io.github.libtmux.Commands
import io.github.libtmux.Environment
import io.github.libtmux.Hooks
import io.github.libtmux.Keys
import io.github.libtmux.MessageLog
import io.github.libtmux.Options
import io.github.libtmux.Prompt
import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerIdentity
import io.github.libtmux.Shell
import io.github.libtmux.batch.Batch
import io.github.libtmux.exception.CardinalityException
import io.github.libtmux.exception.DispatchException
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

    /** Collects several commands to run in one tmux invocation. */
    public fun batch(): Batch = java.batch()

    /** Starts a chain of commands where each one acts on what the last one made. */
    public fun chain(): CommandChain = java.chain()

    /** Shell commands run by tmux, and tmux commands chosen by a shell exit status. */
    public fun shell(): Shell = java.shell()

    /** The commands this tmux knows. */
    public fun commands(): Commands = java.commands()

    /** The server's message log. */
    public fun messageLog(): MessageLog = java.messageLog()

    /** The command prompt's history. */
    public fun prompt(): Prompt = java.prompt()

    /** One of this server's wait-for channels, which is where a signal is sent and waited for. */
    public fun channel(name: String): Channel = java.channel(name)

    /** The server's key bindings: `prefix` when binding, every table when listing. */
    public fun keys(): Keys = java.keys()

    /** The server's paste buffers, which every session shares. */
    public fun buffers(): Buffers = java.buffers()

    /** The server-wide options, the ones tmux keeps once per server. */
    public fun options(): Options = java.options()

    /** The global session options every session inherits unless it sets its own. */
    public fun globalOptions(): Options = java.globalOptions()

    /** The server's environment, which every session inherits and every new process is given. */
    public fun environment(): Environment = java.environment()

    /** The global hooks every session inherits. */
    public fun hooks(): Hooks = java.hooks()

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
            try {
                ControlClient(java.control(session.java, timeout.toJavaDuration()), this)
            } catch (failed: DispatchException.Failed) {
                // ControlWriter.await catches its own InterruptedException and reports it as a
                // checked DispatchException.Failed — the right call for a caller with no coroutine
                // concept, but it swallows the raw InterruptedException runInterruptible (above)
                // needs to translate a cancelling interrupt into a CancellationException. Nothing
                // else interrupts this thread, so an interrupted cause here always means this call
                // was cancelled, never a genuine dispatch failure; reinstate it.
                throw failed.cause as? InterruptedException ?: failed
            }
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
