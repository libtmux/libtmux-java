package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.{Async, Resource}
import _root_.cats.effect.kernel.{Deferred, Fiber, Outcome, Poll}
import _root_.cats.effect.std.{Semaphore, Supervisor}
import _root_.cats.syntax.all._
import io.github.libtmux.transport.{DispatchOutcome, TmuxTransportException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration._

private[cats] final class Execution[F[_]] private[cats] (
    total: Semaphore[F],
    waiters: Option[Semaphore[F]],
    supervisor: Supervisor[F],
    closed: AtomicBoolean
)(implicit F: Async[F]) {
  def apply[A](operation: => A): F[A] = {
    val stash = new AtomicReference[Throwable]
    supervise(total.permit.use(_ => interruptible(stash, operation)), stash)
  }

  def waiting[A](operation: => A): F[A] = waiters match {
    case None =>
      F.raiseError(
        new IllegalStateException(
          "shared waits require capacity of at least two calls"
        )
      )
    case Some(permits) =>
      val stash = new AtomicReference[Throwable]
      // A queued waiter must not consume the permit needed for its signal.
      supervise(
        permits.permit.use(_ =>
          total.permit.use(_ => interruptible(stash, operation))
        ),
        stash
      )
  }

  private def interruptible[A](
      stash: AtomicReference[Throwable],
      operation: => A
  ): F[A] =
    F.interruptible {
      try operation
      catch {
        case failure: TmuxTransportException
            if failure.outcome() == DispatchOutcome.UNKNOWN =>
          stash.set(failure)
          Thread.interrupted()
          throw failure
      }
    }

  private def supervise[A](
      operation: F[A],
      stash: AtomicReference[Throwable]
  ): F[A] =
    F.delay {
      val visible = Execution.fiberVisible
      if (!visible) Execution.enableFiberTracking()
      visible
    }.flatMap {
      case true  => superviseObservingCancel(operation, stash)
      case false =>
        F.cede *> F.delay(Execution.fiberVisible).flatMap { visible =>
          if (visible) superviseObservingCancel(operation, stash)
          else superviseLegacy(operation)
        }
    }

  private def superviseObservingCancel[A](
      operation: F[A],
      stash: AtomicReference[Throwable]
  ): F[A] =
    F.uncancelable { poll =>
      refuseClosed *> supervisor.supervise(operation).flatMap { fiber =>
        F.deferred[Outcome[F, Throwable, A]].flatMap { done =>
          F.start(fiber.join.flatMap(done.complete(_).void)).flatMap { joiner =>
            F.guarantee(watch(fiber, done), joiner.cancel) *>
              done.get.flatMap(finish(poll, stash, _))
          }
        }
      }
    }

  // Cats-effect drops an interruptible result after it interrupts the thread.
  // The cancel flag is visible while this fiber is still masked, so the stashed
  // unknown failure can be raised before that mask ends.
  private def watch[A](
      fiber: Fiber[F, Throwable, A],
      done: Deferred[F, Outcome[F, Throwable, A]]
  ): F[Unit] = {
    val sent = new AtomicBoolean(false)
    def loop: F[Unit] =
      done.tryGet.flatMap {
        case Some(_) => F.unit
        case None    =>
          F.delay(
            Execution.cancelRequested() && sent.compareAndSet(false, true)
          ).ifM(fiber.cancel, F.unit) *> F.sleep(5.millis) *> loop
      }
    loop
  }

  private def finish[A](
      poll: Poll[F],
      stash: AtomicReference[Throwable],
      outcome: Outcome[F, Throwable, A]
  ): F[A] = {
    val saved = stash.get()
    outcome match {
      case Outcome.Succeeded(value)                                  => value
      case Outcome.Errored(_: InterruptedException) if saved == null =>
        asCancel(poll)
      case Outcome.Errored(error)              => F.raiseError(error)
      case Outcome.Canceled() if saved != null => F.raiseError(saved)
      case Outcome.Canceled()                  => asCancel(poll)
    }
  }

  private def superviseLegacy[A](operation: F[A]): F[A] =
    F.uncancelable { poll =>
      refuseClosed *> supervisor.supervise(operation).flatMap { fiber =>
        F.onCancel(poll(fiber.join), fiber.cancel)
          .flatMap(_.embed(asCancel(poll)))
      }
    }

  private def asCancel[A](poll: Poll[F]): F[A] =
    poll(
      F.canceled *> F.raiseError(
        new IllegalStateException("Scala client scope is closed")
      )
    )

  private def refuseClosed: F[Unit] =
    F.delay {
      if (closed.get())
        throw new IllegalStateException("Scala client scope is closed")
    }
}

private[cats] object Execution {
  private val trackingEnabled = new AtomicBoolean(false)

  // Cats Effect publishes the running fiber only when this flag is set, and
  // it reads the flag once per resume. A masked fiber cannot see cancel
  // any other way.
  private def enableFiberTracking(): Unit =
    if (trackingEnabled.compareAndSet(false, true))
      try enableFiberTrackingOnce()
      catch { case _: Exception => () }

  private def enableFiberTrackingOnce(): Unit = {
    val constants = Class.forName("cats.effect.IOFiberConstants")
    val field = constants.getDeclaredField("TrackFiberContext")
    val unsafeField =
      Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    unsafeField.setAccessible(true)
    val unsafe = unsafeField.get(null)
    val unsafeClass = unsafe.getClass
    val base = unsafeClass
      .getMethod("staticFieldBase", classOf[java.lang.reflect.Field])
      .invoke(unsafe, field)
    val offset = unsafeClass
      .getMethod("staticFieldOffset", classOf[java.lang.reflect.Field])
      .invoke(unsafe, field)
      .asInstanceOf[Long]
    val already = unsafeClass
      .getMethod("getBoolean", classOf[Object], classOf[Long])
      .invoke(unsafe, base, java.lang.Long.valueOf(offset))
      .asInstanceOf[Boolean]
    if (!already)
      unsafeClass
        .getMethod(
          "putBoolean",
          classOf[Object],
          classOf[Long],
          classOf[Boolean]
        )
        .invoke(
          unsafe,
          base,
          java.lang.Long.valueOf(offset),
          java.lang.Boolean.TRUE
        )
    ()
  }

  private val ioFiberClass: Option[Class[_]] =
    try Some(Class.forName("cats.effect.IOFiber"))
    catch { case _: ClassNotFoundException => None }

  private val cancelFlag: Option[java.lang.reflect.Field] =
    ioFiberClass.flatMap { clazz =>
      try {
        val field = clazz.getDeclaredField("canceled")
        field.setAccessible(true)
        Some(field)
      } catch {
        case _: ReflectiveOperationException | _: SecurityException => None
      }
    }

  private val currentFiber: Option[java.lang.reflect.Method] =
    ioFiberClass.flatMap { clazz =>
      try Some(clazz.getMethod("currentIOFiber"))
      catch { case _: ReflectiveOperationException => None }
    }

  private def fiberVisible: Boolean =
    currentFiber.exists(_.invoke(null) != null) && cancelFlag.isDefined

  private def cancelRequested(): Boolean =
    (cancelFlag, currentFiber) match {
      case (Some(field), Some(method)) =>
        val fiber = method.invoke(null)
        fiber != null && field.getBoolean(fiber)
      case _ => false
    }

  def resource[F[_]: Async](capacity: Int): Resource[F, Execution[F]] = {
    val F = Async[F]
    for {
      _ <- Resource.eval(
        F.delay(require(capacity >= 1, "capacity must be positive"))
      )
      total <- Resource.eval(Semaphore[F](capacity.toLong))
      waiters <- Resource.eval(
        if (capacity == 1) F.pure(Option.empty[Semaphore[F]])
        else Semaphore[F](capacity.toLong - 1).map(Some(_))
      )
      supervisor <- Supervisor[F](await = false)
      closed <- Resource.make(F.delay(new AtomicBoolean(false)))(flag =>
        F.delay(flag.set(true))
      )
    } yield new Execution(total, waiters, supervisor, closed)
  }
}
