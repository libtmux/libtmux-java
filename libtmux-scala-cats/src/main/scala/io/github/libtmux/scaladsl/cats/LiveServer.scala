package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.{Async, Deferred, Outcome, Resource}
import _root_.cats.effect.syntax.all._
import _root_.cats.syntax.all._
import fs2.concurrent.{Signal, SignallingRef}
import io.github.libtmux.snapshot.{ServerMirror => JavaServerMirror}
import java.time.Duration
import scala.jdk.OptionConverters._

/** A live view of one tmux server, wrapping Java's own
  * [[JavaServerMirror ServerMirror]] with a `Signal` whose background poll
  * fiber's outcome is observed and surfaced, rather than discarded: a
  * `.background`ed poll loop whose returned `F[Outcome[...]]` is never read
  * leaves a dead poller silently stale, with the signal simply going quiet.
  * Here the loop's own success, error or cancellation completes a `Deferred` —
  * via `guaranteeCase`, before `.background` detaches the fiber — so
  * [[failure]] can be asked at any time.
  */
final class LiveServer[F[_]] private (
    val signal: Signal[F, JavaServerMirror.View],
    private val outcomeD: Deferred[F, Either[Throwable, Unit]]
) {

  /** `None` while still live; `Some(cause)` once the background poller has
    * died. Never blocks.
    */
  def failure(implicit F: _root_.cats.Functor[F]): F[Option[Throwable]] =
    outcomeD.tryGet.map(_.flatMap(_.left.toOption))
}

object LiveServer {

  private val AwaitBudget: Duration = Duration.ofHours(24)

  /** Listens through a control client attached to `anchor`, rebuilding on every
    * announcement. The session is a required parameter: there is no arbitrary
    * pick left to make, and a session that later disappears is `ServerMirror`'s
    * own reconnect-or-`TargetGoneException` policy, surfaced through
    * [[LiveServer#failure]] like any other cause.
    */
  def attach[F[_]](
      anchor: Session[F]
  )(implicit F: Async[F]): Resource[F, LiveServer[F]] =
    for {
      mirror <- Resource.make(
        F.interruptible(JavaServerMirror.open(anchor.underlying.asJava))
      )(m => F.interruptible(m.close()))
      initial <- Resource.eval(F.delay(mirror.current()))
      ref <- Resource.eval(SignallingRef.of[F, JavaServerMirror.View](initial))
      outcomeD <- Resource.eval(Deferred[F, Either[Throwable, Unit]])
      _ <- pollLoop(mirror, ref, initial.epoch()).guaranteeCase {
        case Outcome.Succeeded(_) => outcomeD.complete(Right(())).void
        case Outcome.Errored(e)   => outcomeD.complete(Left(e)).void
        case Outcome.Canceled()   => outcomeD.complete(Right(())).void
      }.background
    } yield new LiveServer(ref, outcomeD)

  /** Waits from the epoch the signal was seeded with, not a second read of
    * `current()`: a view published between two reads would never reach the
    * signal.
    */
  private def pollLoop[F[_]](
      mirror: JavaServerMirror,
      ref: SignallingRef[F, JavaServerMirror.View],
      seeded: Long
  )(implicit
      F: Async[F]
  ): F[Unit] = {
    def step(epoch: Long): F[Unit] =
      F.interruptible(mirror.awaitNewer(epoch, AwaitBudget)).flatMap { newer =>
        newer.toScala match {
          case Some(view)             => ref.set(view) *> step(view.epoch())
          case None if mirror.isEnded =>
            mirror.cause().toScala.fold(F.unit)(F.raiseError)
          case None =>
            step(epoch) // AwaitBudget elapsed with no change; keep waiting.
        }
      }
    step(seeded)
  }
}
