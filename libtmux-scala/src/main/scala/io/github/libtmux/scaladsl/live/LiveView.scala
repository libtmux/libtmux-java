package io.github.libtmux.scaladsl.live

import io.github.libtmux.scaladsl.Session
import io.github.libtmux.snapshot.{ServerMirror => JavaServerMirror}
import java.time.Duration
import scala.jdk.OptionConverters._

/** A live view of one tmux server's sessions, windows, panes and clients,
  * opaque over Java's own [[JavaServerMirror ServerMirror]] — re-snapshotting
  * on notification, on a gap and on reconnect is entirely Java's job; this only
  * wraps it, rather than re-deriving resnapshot-on-notification from a raw
  * control subscription. `close()` comes from the `AutoCloseable` bound with no
  * forwarding of its own.
  */
opaque type LiveView <: AutoCloseable = JavaServerMirror

object LiveView {

  private[live] val AwaitBudget: Duration = Duration.ofHours(24)

  /** Listens through a control client attached to `anchor`, rebuilding on every
    * announcement.
    */
  def attach(anchor: Session): LiveView = JavaServerMirror.open(anchor.asJava)

  /** As [[attach(anchor:io\.github\.libtmux\.scaladsl\.Session)* attach]], also
    * rebuilding when nothing has been announced for `refreshEvery`.
    */
  def attach(anchor: Session, refreshEvery: Duration): LiveView =
    JavaServerMirror.open(anchor.asJava, refreshEvery)

  // Nested, not top-level: see Pane.scala's own asJava for why a same-named top-level extension
  // split across files does not work.
  extension (self: LiveView) {

    /** The underlying Java mirror. Opaque wrapping is free, so this never
      * copies scope or state.
      */
    def asJava: JavaServerMirror = self

    /** The latest published view. Never blocks. */
    def current: JavaServerMirror.View = self.current()

    /** Waits for a view newer than `epoch`, or empty when `timeout` passed
      * first or this mirror ended; [[isEnded]] and [[cause]] tell those apart.
      */
    def awaitNewer(
        epoch: Long,
        timeout: Duration
    ): Option[JavaServerMirror.View] =
      self.awaitNewer(epoch, timeout).toScala

    /** Whether this mirror has stopped publishing, because it was closed or its
      * anchor has gone.
      */
    def isEnded: Boolean = self.isEnded()

    /** Why this mirror ended, when something other than [[close]] ended it. */
    def cause: Option[Throwable] = self.cause().toScala

    /** The current view, then every newer one, one per notification, blocking
      * up to a day between them: as a `StateFlow` or an fs2 `Signal` does. A
      * change made before this call is in the first view rather than lost
      * between reading [[current]] and starting to iterate.
      */
    def snapshots: Iterator[JavaServerMirror.View] = {
      val now = self.current()
      Iterator.single(now) ++ snapshotsAfter(now.epoch())
    }

    /** Every view newer than `epoch`, one per notification, blocking up to a
      * day between them. A view already published after `epoch` comes first, so
      * nothing is missed between reading a view and starting to iterate.
      */
    def snapshotsAfter(epoch: Long): Iterator[JavaServerMirror.View] =
      Iterator.unfold(epoch) { previous =>
        self
          .awaitNewer(previous, LiveView.AwaitBudget)
          .toScala
          .map(next => (next, next.epoch()))
      }
  }
}
