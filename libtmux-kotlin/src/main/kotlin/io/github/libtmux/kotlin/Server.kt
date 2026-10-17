package io.github.libtmux.kotlin

import io.github.libtmux.Channel
import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerIdentity
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
 * Manages sessions, windows, and panes on a tmux server.
 *
 * Use [open] with a [ServerConfig] to select a server by socket name or path. From this object,
 * create sessions or query the server's existing sessions, windows, and panes. Each [Session]
 * contains windows, and each [Window] contains panes.
 *
 * Operations that contact tmux are `suspend` functions dispatched through [policy]. Call [close]
 * after the last operation to release resources owned by this object. The tmux server and its
 * sessions keep running.
 *
 * [asJava] exposes the underlying [JavaServer] for interoperability. Both objects share the same
 * resources, so closing either closes both.
 *
 * # What is on here
 *
 * A tmux server does a great many things, so this class has a great many members. They fall into a
 * few groups:
 *
 * **Connecting.** [open] makes a server over a transport it owns, [withServer] does so for the
 * length of a block and closes it however the block ends, and [fromJava] wraps a server opened in
 * Java. [config] says how it was configured, [identity] which server it is, and [version] which
 * tmux. [isAlive] and [requireAlive] answer whether anything is listening. [admissionBound] says
 * how many commands run at once, and [policy] decides how the `suspend` functions are dispatched.
 * [control] attaches a control client, whose output and events arrive as cold `Flow`s, and
 * [withControl] scopes one to a block. [close] releases what the server owns.
 *
 * **Finding one thing.** Given a filter, [session], [window], and [pane] throw unless exactly one
 * object matches, and [sessionOrNull], [windowOrNull], and [paneOrNull] return null when none does;
 * several matches throw either way. [session] by name or `SessionId`, [pane] by `PaneId`, and
 * [window] by `WindowContext` return null when tmux does not have it. [hasSession] answers by name
 * without returning the session.
 *
 * **Listing everything.** [sessions], [windows], [panes], [clients], and [attachedSessions] each
 * return a `List` captured now. A failed read throws, so an outage does not read as an empty
 * server. [windows] also takes a `WindowId` and answers every link to that window. [snapshot]
 * captures the whole tree with one listing per kind of object rather than one command per object,
 * and [liveState] publishes it again as a `StateFlow` whenever tmux announces a change;
 * [withLiveState] scopes that to a block.
 *
 * **Querying things and getting around.** Hand [sessions], [windows], [panes], [session], [window],
 * or [pane] a filter built from the field properties on the handle companions, such as
 * `Pane.command startsWith "nvim"`, and tmux does the filtering when it can. To move between
 * levels, go down with [Session.windows] and [Window.panes], up with [Pane.window] and
 * [Window.session], and across to the focused child with [Session.activeWindow] and
 * [Window.activePane]. Each is a pure read of the capture, so none suspends or asks tmux again.
 *
 * **Changing things.** [newSession] creates a detached session from a name, a `SessionSpec`, or a
 * builder block, and returns the session tmux made. [killSession] ends a session by name,
 * [killServer] ends the tmux server and every session on it, which [close] deliberately does not,
 * and [lock] locks every attached client.
 *
 * **Options and hooks.** [options] holds the server-wide options, [globalOptions] the session
 * options every session inherits unless it sets its own, [hooks] the global hooks, and
 * [environment] the variables every new process is given. Each returns a wrapper whose own
 * operations are `suspend`.
 *
 * **Everything else tmux keeps.** Paste buffers ([buffers]), key bindings ([keys]), a file of tmux
 * commands to run ([sourceFile]), shell commands ([shell]), the command list ([commands]), the
 * message log ([messageLog]), prompt history ([prompt]), and wait-for channels ([channel]).
 * [expand][Server.expand] expands a format against the server, [variables][Server.variables] reads
 * named variables in one expansion, and [paneFields] reads chosen fields of every pane from one
 * listing.
 *
 * Anything tmux can do that is not here is reachable through [cmd], which runs one command and
 * hands back its result, [run][Server.run], which throws when tmux reports a nonzero exit, [batch]
 * and [chain], which run several in one invocation, or [asJava], for an operation this class does
 * not mirror.
 */
public class Server private constructor(
    internal val java: JavaServer,
    public val policy: ExecutionPolicy,
) : AutoCloseable {

    /**
     * The Java server this wraps, for an operation this class does not mirror or a Java API that
     * takes one. The same object, not a copy: closing either closes both.
     */
    public val asJava: JavaServer get() = java

    /** How this server was configured. */
    public val config: ServerConfig get() = java.config()

    /** Which server this is. Every handle taken from it is scoped by this. */
    public val identity: ServerIdentity get() = java.identity()

    /** How many tmux commands this server's transport runs at once, from [ExecutionPolicy.default]. */
    public val admissionBound: Int get() = java.admissionBound()

    /** Collects several commands to run in one tmux invocation. */
    public fun batch(): Batch = Batch(java.batch(), this)

    /** Starts a chain of commands where each one acts on what the last one made. */
    public fun chain(): CommandChain = CommandChain(java.chain(), this)

    /** Shell commands run by tmux, and tmux commands chosen by a shell exit status. */
    public fun shell(): Shell = Shell(java.shell(), this)

    /** The commands this tmux knows. */
    public fun commands(): Commands = Commands(java.commands(), this)

    /** The server's message log. */
    public fun messageLog(): MessageLog = MessageLog(java.messageLog(), this)

    /** The command prompt's history. */
    public fun prompt(): Prompt = Prompt(java.prompt(), this)

    /** One of this server's wait-for channels, which is where a signal is sent and waited for. */
    public fun channel(name: String): Channel = java.channel(name)

    /** The server's key bindings: `prefix` when binding, every table when listing. */
    public fun keys(): Keys = Keys(java.keys(), this)

    /** The server's paste buffers, which every session shares. */
    public fun buffers(): Buffers = Buffers(java.buffers(), this)

    /** The server-wide options, the ones tmux keeps once per server. */
    public fun options(): Options = Options(java.options(), this)

    /** The global session options every session inherits unless it sets its own. */
    public fun globalOptions(): Options = Options(java.globalOptions(), this)

    /** The server's environment, which every session inherits and every new process is given. */
    public fun environment(): Environment = Environment(java.environment(), this)

    /** The global hooks every session inherits. */
    public fun hooks(): Hooks = Hooks(java.hooks(), this)

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

        /**
         * A wrapper over a server opened in Java, such as a test fixture's. It takes the server as
         * it is: closing the wrapper closes it.
         */
        public fun fromJava(
            server: JavaServer,
            policy: ExecutionPolicy = ExecutionPolicy.default(server.config()),
        ): Server = Server(server, policy)
    }
}
