package io.github.libtmux.scaladsl

import io.github.libtmux.{Pane => JavaPane, PaneRun, TextOutcome, WakeReason}
import io.github.libtmux.scaladsl.query.Expr
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters._

/** A captured pane occurrence, opaque over the Java handle. Java equality
  * identifies the physical pane; every operation beyond that is an extension,
  * generated from the operation catalog or handwritten where the catalog marks
  * it `WAIT`, `STREAM` or `LIFECYCLE`.
  *
  * A pure read of the capture issues no command; every other operation asks tmux
  * now, blocking the calling thread.
  *
  * # What is on here
  *
  * The operations fall into a few groups:
  *
  * **Reading the capture.** [[Pane.id]], [[Pane.index]], [[Pane.active]],
  * [[Pane.currentCommand]], [[Pane.floating]], [[Pane.size]], [[Pane.position]],
  * [[Pane.edges]], [[Pane.title]], [[Pane.currentPath]],
  * [[Pane.currentPathText]] and [[Pane.pid]] read what this capture stored about
  * the pane, and [[Pane.window]] and [[Pane.server]] are what it sits in. The id
  * is stable and the index is state, since panes renumber as neighbours come and
  * go. [[Pane.floating]] is empty when the running tmux cannot say, which before
  * 3.7 is always, and [[Pane.pid]] is empty when tmux reported no process.
  * [[Pane.currentPath]] throws when this JVM cannot represent the directory's
  * name; [[Pane.currentPathText]] is what tmux reported and never throws.
  *
  * [[Pane.info]], [[Pane.id]], [[Pane.index]], [[Pane.active]],
  * [[Pane.currentCommand]], [[Pane.floating]], [[Pane.size]], [[Pane.position]],
  * [[Pane.edges]], [[Pane.title]], [[Pane.currentPath]],
  * [[Pane.currentPathText]], [[Pane.pid]], [[Pane.window]], [[Pane.server]] and
  * [[Pane.asJava]]
  *
  * **Sending input.** [[Pane.send]] types keys without pressing Enter and
  * [[Pane.sendLine]] presses it, which is how a command gets run.
  * [[Pane.sendKeys]] resolves each entry through tmux's key table and types an
  * unknown name as text; [[Pane.sendLiteral]] types every entry as the
  * characters it spells, so `C-c` is three characters. [[Pane.paste]] delivers
  * text as one block through a private buffer that is removed afterwards, and it
  * throws for text containing NUL and before tmux 3.4. [[Pane.noteTyped]] tells
  * a later wait about text that reached the pane without passing through this
  * handle.
  *
  * [[Pane.send]], [[Pane.sendLine]], [[Pane.sendKeys]], [[Pane.sendLiteral]],
  * [[Pane.paste]], [[Pane.pasteBuffer]] and [[Pane.noteTyped]]
  *
  * **Reading output.** [[Pane.capture]] returns the visible content as a
  * `Vector` with one element per line, read from tmux now rather than from the
  * capture. Give it a callback on a builder or a `CaptureSpec` to read part of
  * the history; it throws when the spec asks for something this server does not
  * have. [[Pane.pipeTo]] sends everything the pane prints to a shell command
  * until [[Pane.stopPiping]], and [[Pane.clearHistory]] discards the scrollback.
  *
  * [[Pane.capture]], [[Pane.pipeTo]], [[Pane.stopPiping]] and
  * [[Pane.clearHistory]]
  *
  * **Running and waiting.** [[Pane.run]] runs a shell command to its end and
  * answers with its exit status and output, and throws when the pane is not
  * running a POSIX shell. Reach for it first when the command is yours. When it
  * is not, [[Pane.awaitText]] and [[Pane.await]] poll the screen or the pane's
  * state on a timer and answer with why they ended; a pane killed mid-wait ends
  * [[Pane.awaitText]] as `TextOutcome.PANE_GONE` rather than an exception. Each
  * wait blocks its thread, and interrupting the thread cancels it with
  * `InterruptedException`. [[Pane.dead]] reads fresh whether the pane's process
  * has exited, and throws when the pane is gone from a server that still
  * answers.
  *
  * [[Pane.run]], [[Pane.awaitText]], [[Pane.await]] and [[Pane.dead]]
  *
  * **Splitting and arranging.** [[Pane.split]] divides this pane in half, from a
  * callback on a builder or a `SplitSpec`, and returns the pane that appeared.
  * [[Pane.breakOut]] moves the pane into a window of its own, [[Pane.joinTo]]
  * into another window, and [[Pane.swapWith]] swaps it with another pane.
  * [[Pane.resize]] grows it by cells in one direction, [[Pane.resizeTo]] sets
  * exact dimensions, and [[Pane.select]] makes it the active pane. An unnamed
  * [[Pane.breakOut]] throws on exact tmux 3.7, which a named break or 3.7a
  * avoids.
  *
  * [[Pane.split]], [[Pane.breakOut]], [[Pane.joinTo]], [[Pane.swapWith]],
  * [[Pane.resize]], [[Pane.resizeTo]] and [[Pane.select]]
  *
  * **Modes.** [[Pane.copyMode]] puts the pane into copy mode, [[Pane.mode]]
  * reads live which mode it is in, empty when it is showing its program, and
  * [[Pane.exitMode]] leaves whichever mode it is in.
  *
  * [[Pane.copyMode]], [[Pane.mode]] and [[Pane.exitMode]]
  *
  * **Changing the pane.** [[Pane.retitle]] returns a replacement capture,
  * because this handle keeps its original state. [[Pane.respawn]] kills what
  * runs here and starts the pane's default command or one you give,
  * [[Pane.respawnIn]] does so in a directory, and [[Pane.kill]] closes the pane.
  *
  * [[Pane.retitle]], [[Pane.respawn]], [[Pane.respawnIn]] and [[Pane.kill]]
  *
  * **Options and hooks.** [[Pane.options]] and [[Pane.hooks]] reach what is set
  * on this pane alone.
  *
  * [[Pane.options]] and [[Pane.hooks]]
  *
  * **Formats and refreshing.** [[Pane.expand]] expands a tmux format, and
  * [[Pane.variables]] reads several named variables, in this pane's context.
  * [[Pane.refresh]] takes a new capture to see the pane as it is now. It leaves
  * this handle unchanged and returns the new one, and throws if the pane is gone
  * from a server that still answers.
  *
  * [[Pane.expand]], [[Pane.variables]] and [[Pane.refresh]]
  */
opaque type Pane = JavaPane

object Pane {

  private[scaladsl] def wrap(java: JavaPane): Pane = java

  given CanEqual[Pane, Pane] = CanEqual.derived

  // Generated from field-catalog.tsv (project/ScalaFieldCodegen.scala), in its own object and
  // package so it can live in a separate generated file: a class and object of the same name are
  // companions, sharing private access, only when they share one file, so a generated `object
  // Pane` here would either fail to compile alongside this one or silently stop being this type's
  // companion. Re-exporting is the one-line bridge back.
  export io.github.libtmux.scaladsl.generated.PaneFields.*

  // Nested, not top-level: a top-level `extension (self: Pane) def asJava` in this file would share
  // a name with Server/Session/Window/Client's own top-level `asJava`, and Scala 3 requires
  // same-named top-level definitions to share one compilation unit ("the same group of toplevel
  // definitions"). Nested here, it is found through Pane's own extension-method implicit scope
  // instead, with no cross-file collision.
  extension (self: Pane) {

    /** The underlying Java pane. Opaque wrapping is free, so this never copies
      * scope or state.
      */
    def asJava: JavaPane = self

    // WAIT, handwritten: bespoke cancellation and a deadline, not a per-operation forward.
    // Each blocks its thread; interrupting that thread cancels the wait with InterruptedException,
    // as the Java method declares.

    /** Waits until `text` appears, or `timeout` passes first. A pane killed
      * mid-wait answers `TextOutcome.PANE_GONE` rather than raising.
      */
    def awaitText(text: String, timeout: FiniteDuration): TextOutcome =
      self.asJava.awaitText(text, timeout.toJava)

    /** As `awaitText(text, timeout)`, looking every `every`: each look is a
      * tmux process.
      */
    def awaitText(
        text: String,
        timeout: FiniteDuration,
        every: FiniteDuration
    ): TextOutcome =
      self.asJava.awaitText(text, timeout.toJava, every.toJava)

    /** Runs a shell command in this pane to its end, and answers with its exit
      * status and output.
      */
    def run(command: String, timeout: FiniteDuration): PaneRun =
      self.asJava.run(command, timeout.toJava)

    /** Waits until `settled` holds for this pane as it is now. `settled`
      * receives a fresh capture each look.
      */
    def await(settled: Pane => Boolean, timeout: FiniteDuration): WakeReason =
      self.asJava.await(fresh => settled(Pane.wrap(fresh)), timeout.toJava)

    /** As `await(settled, timeout)`, looking every `every`. */
    def await(
        settled: Pane => Boolean,
        timeout: FiniteDuration,
        every: FiniteDuration
    ): WakeReason =
      self.asJava.await(
        fresh => settled(Pane.wrap(fresh)),
        timeout.toJava,
        every.toJava
      )
  }

  extension (panes: Vector[Pane]) {

    /** The captured panes `expr` matches: a local filter, evaluated against
      * what is already held.
      */
    def matching(expr: Expr[JavaPane]): Vector[Pane] =
      panes.filter(pane => expr.matches(pane.asJava))
  }
}
