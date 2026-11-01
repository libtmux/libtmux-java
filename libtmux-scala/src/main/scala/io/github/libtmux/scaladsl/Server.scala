package io.github.libtmux.scaladsl

import io.github.libtmux.{Server => JavaServer, ServerConfig}
import io.github.libtmux.control.ControlClient
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters._

/** Manages sessions, windows, and panes on a tmux server.
  *
  * Use [[Server.open]] with a [[io.github.libtmux.ServerConfig ServerConfig]]
  * to select a server by socket name or path. From this object, create sessions
  * or query the server's existing sessions, windows, and panes. Each
  * [[Session]] contains windows, and each [[Window]] contains panes.
  *
  * The Java client, opaque: every member beyond
  * [[java.lang.AutoCloseable#close]] is an extension, generated from the
  * operation catalog or handwritten where the catalog marks it `WAIT`, `STREAM`
  * or `LIFECYCLE`. Closing an owned client releases its transport, not tmux;
  * `close()` comes from the `AutoCloseable` bound with no forwarding of its
  * own, so `Using.resource(server) { ... }` works.
  *
  * # What is on here
  *
  * A tmux server does a great many things, so this type has a great many
  * members. They fall into a few groups:
  *
  * **Connecting.** [[Server.open]] makes a server over a transport it owns, and
  * [[Server.fromJava]] wraps a server opened in Java. [[config]] says how it
  * was configured, [[Server.identity]] which server it is, and [[version]]
  * which tmux. [[isAlive]] and [[requireAlive]] answer whether anything is
  * listening. [[Server.within]] bounds every command by a deadline,
  * [[admissionBound]] says how many commands run at once, and
  * [[Server.control]] attaches a control client. [[toBuilder]] starts a Java
  * builder from this server's choices, and [[Server.asJava]] reaches the
  * underlying Java server.
  *
  * [[Server.open]], [[Server.fromJava]], [[config]], [[Server.identity]],
  * [[version]], [[isAlive]], [[requireAlive]], [[Server.within]],
  * [[admissionBound]], [[Server.control]], [[toBuilder]] and [[Server.asJava]]
  *
  * **Finding one thing.** [[session]] takes a name, an id, or an expression;
  * [[pane]] takes an id or an expression; [[window]] takes an expression or a
  * `WindowContext`. Each returns an `Option` that is empty when tmux does not
  * have it, and an expression that matches several throws
  * `CardinalityException.MultipleMatches`. [[hasSession]] answers by name
  * without returning the session.
  *
  * [[session]], [[pane]], [[window]] and [[hasSession]]
  *
  * **Listing everything.** [[sessions]], [[windows]], [[panes]], [[clients]],
  * and [[attachedSessions]] each return a `Vector` captured now. A failed read
  * throws, so an outage does not read as an empty server. [[windows]] also
  * takes a `WindowId` and answers every link to that window. [[snapshot]]
  * captures the whole tree with one listing per kind of object rather than one
  * command per object, and [[io.github.libtmux.scaladsl.live.LiveView]] keeps
  * that tree current as tmux announces changes.
  *
  * [[sessions]], [[windows]], [[panes]], [[clients]], [[attachedSessions]] and
  * [[snapshot]]
  *
  * **Querying things and getting around.** Build an
  * [[io.github.libtmux.scaladsl.query.Expr Expr]] from the fields on the
  * `Session`, `Window`, and `Pane` companions, such as
  * `Pane.command.startsWith("nvim")`, and hand it to [[sessions]], [[windows]],
  * [[panes]], [[session]], [[window]], or [[pane]]; tmux does the filtering
  * when it can. To move between levels, go down with [[Session.windows]] and
  * [[Window.panes]], up with [[Pane.window]] and [[Window.session]], and across
  * to the focused child with [[Session.activeWindow]] and
  * [[Window.activePane]]. Each is a pure read of the capture, so none asks tmux
  * again.
  *
  * [[sessions]], [[windows]], [[panes]], [[session]], [[window]], [[pane]],
  * [[Session.windows]], [[Window.panes]], [[Pane.window]], [[Window.session]],
  * [[Session.activeWindow]] and [[Window.activePane]]
  *
  * **Changing things.** [[newSession]] creates a detached session from a name,
  * a callback on a builder, or a `SessionSpec`, and returns the session tmux
  * made. [[killSession]] ends a session by name, [[killServer]] ends the tmux
  * server and every session on it, which `close()` deliberately does not, and
  * [[lock]] locks every attached client.
  *
  * [[newSession]], [[killSession]], [[killServer]] and [[lock]]
  *
  * **Options and hooks.** [[options]] holds the server-wide options,
  * [[globalOptions]] the session options every session inherits unless it sets
  * its own, [[hooks]] the global hooks, and [[environment]] the variables every
  * new process is given.
  *
  * [[options]], [[globalOptions]], [[hooks]] and [[environment]]
  *
  * **Everything else tmux keeps.** Paste buffers ([[buffers]]), key bindings
  * ([[keys]]), a file of tmux commands to run ([[sourceFile]]), shell commands
  * ([[shell]]), the command list ([[commands]]), the message log
  * ([[messageLog]]), prompt history ([[prompt]]), and wait-for channels
  * ([[channel]]). [[expand]] expands a format against the server, [[variables]]
  * reads named variables in one expansion, and [[paneFields]] reads chosen
  * fields of every pane from one listing.
  *
  * [[buffers]], [[keys]], [[sourceFile]], [[shell]], [[commands]],
  * [[messageLog]], [[prompt]], [[channel]], [[expand]], [[variables]] and
  * [[paneFields]]
  *
  * **Running a command.** Anything tmux can do that is not here is reachable
  * through [[cmd]], which runs one command and hands back its result, [[run]],
  * which throws when tmux reports a nonzero exit, or [[batch]] and [[chain]],
  * which run several in one invocation.
  *
  * [[cmd]], [[run]], [[batch]] and [[chain]]
  */
opaque type Server <: AutoCloseable = JavaServer

object Server {

  /** Opens an owned client for `config`. */
  def open(config: ServerConfig): Server = JavaServer.open(config)

  /** Borrows a Java client this facade did not open; closing it closes that
    * client too.
    */
  def fromJava(java: JavaServer): Server = java

  private[scaladsl] def wrap(java: JavaServer): Server = fromJava(java)

  given CanEqual[Server, Server] = CanEqual.derived

  // Nested, not top-level: a top-level `extension (self: Server) def asJava` in this file would
  // share a name with Session/Window/Pane/Client's own top-level `asJava`, and Scala 3 requires
  // same-named top-level definitions to share one compilation unit ("the same group of toplevel
  // definitions"). Nested here, it is found through Server's own extension-method implicit scope
  // instead, with no cross-file collision.
  extension (self: Server) {

    /** The underlying Java client. Opaque wrapping is free, so this never
      * copies scope or state.
      */
    def asJava: JavaServer = self

    /** This server with every command given `timeout` rather than the default,
      * sharing this one's transport and scope. `LIFECYCLE`, handwritten: not a
      * per-operation forward, a derived scope.
      */
    def within(timeout: FiniteDuration): Server =
      Server.wrap(self.asJava.within(timeout.toJava))

    /** A control-mode client on `session`, refused by a tmux other than the one
      * that captured it. `LIFECYCLE`, handwritten: resource scoping, not a
      * per-operation forward.
      */
    def control(session: Session): ControlClient =
      self.asJava.control(session.asJava)

    /** As [[control(session:io\.github\.libtmux\.scaladsl\.Session)* control]],
      * with an explicit attach timeout.
      */
    def control(session: Session, timeout: FiniteDuration): ControlClient =
      self.asJava.control(session.asJava, timeout.toJava)
  }
}
