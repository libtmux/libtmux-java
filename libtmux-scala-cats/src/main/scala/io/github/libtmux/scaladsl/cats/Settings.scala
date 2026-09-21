package io.github.libtmux.scaladsl.cats

import io.github.libtmux.{
  BufferInfo,
  Buffers => JavaBuffers,
  Channel => JavaChannel,
  Environment => JavaEnvironment,
  Hooks => JavaHooks,
  OptionKey,
  Options => JavaOptions,
  WakeReason
}
import io.github.libtmux.scaladsl.blocking
import java.nio.file.Path
import java.time.Duration
import scala.collection.immutable.VectorMap

/** Lazy option operations at one captured Java scope. */
final class Options[F[_]] private[cats] (
    underlying: blocking.Options,
    execution: Execution[F]
) {
  val asJava: JavaOptions = underlying.asJava
  def get(name: String): F[Option[String]] = execution(underlying.get(name))
  def get[A](key: OptionKey[A]): F[Option[A]] = execution(underlying.get(key))
  def set(name: String, value: String): F[Unit] = execution(
    underlying.set(name, value)
  )
  def set[A](key: OptionKey[A], value: A): F[Unit] = execution(
    underlying.set(key, value)
  )
  def all: F[VectorMap[String, String]] = execution(underlying.all())
  def effective: F[VectorMap[String, String]] = execution(
    underlying.effective()
  )
  def setIfAbsent(name: String, value: String): F[Boolean] = execution(
    underlying.setIfAbsent(name, value)
  )
  def append(name: String, suffix: String): F[Unit] = execution(
    underlying.append(name, suffix)
  )
  def setExpanded(name: String, format: String): F[Unit] = execution(
    underlying.setExpanded(name, format)
  )
  def unset(name: String): F[Unit] = execution(underlying.unset(name))
}

/** Lazy environment operations retaining Java's removal marks. */
final class Environment[F[_]] private[cats] (
    underlying: blocking.Environment,
    execution: Execution[F]
) {
  val asJava: JavaEnvironment = underlying.asJava
  def get(name: String): F[Option[String]] = execution(underlying.get(name))
  def isRemoved(name: String): F[Boolean] = execution(
    underlying.isRemoved(name)
  )
  def all: F[VectorMap[String, String]] = execution(underlying.all())
  def effective: F[VectorMap[String, String]] = execution(
    underlying.effective()
  )
  def removed: F[Set[String]] = execution(underlying.removed())
  def set(name: String, value: String): F[Unit] = execution(
    underlying.set(name, value)
  )
  def setExpanded(name: String, format: String): F[Unit] = execution(
    underlying.setExpanded(name, format)
  )
  def unset(name: String): F[Unit] = execution(underlying.unset(name))
  def remove(name: String): F[Unit] = execution(underlying.remove(name))
}

/** Lazy hook operations. Captured listings retain order, not sparse indices. */
final class Hooks[F[_]] private[cats] (
    underlying: blocking.Hooks,
    execution: Execution[F]
) {
  val asJava: JavaHooks = underlying.asJava
  def set(event: String, command: String): F[Unit] = execution(
    underlying.set(event, command)
  )
  def set(event: String, command: Seq[String]): F[Unit] = execution(
    underlying.set(event, command)
  )
  def append(event: String, command: String): F[Unit] = execution(
    underlying.append(event, command)
  )
  def append(event: String, command: Seq[String]): F[Unit] = execution(
    underlying.append(event, command)
  )
  def unset(event: String): F[Unit] = execution(underlying.unset(event))
  def run(event: String): F[Unit] = execution(underlying.run(event))
  def all: F[VectorMap[String, Vector[String]]] = execution(underlying.all())
}

/** Lazy named-buffer operations with Java's normalized text contract. */
final class Buffers[F[_]] private[cats] (
    underlying: blocking.Buffers,
    execution: Execution[F]
) {
  val asJava: JavaBuffers = underlying.asJava
  def list: F[Vector[BufferInfo]] = execution(underlying.list())
  def set(name: String, contents: String): F[Unit] = execution(
    underlying.set(name, contents)
  )
  def show(name: String): F[String] = execution(underlying.show(name))
  def delete(name: String): F[Unit] = execution(underlying.delete(name))
  def load(name: String, file: Path): F[Unit] = execution(
    underlying.load(name, file)
  )
  def save(name: String, file: Path): F[Unit] = execution(
    underlying.save(name, file)
  )
}

/** Shared waits reserve facade and Java transport capacity for their signal. */
final class Channel[F[_]] private[cats] (
    underlying: blocking.Channel,
    execution: Execution[F]
) {
  val asJava: JavaChannel = underlying.asJava
  val name: String = underlying.name
  def signal: F[Unit] = execution(underlying.signal())
  def await(timeout: Duration): F[WakeReason] =
    execution.waiting(underlying.awaitReservingCapacity(timeout))
  def drain: F[Boolean] = execution(underlying.drain())
  override def toString: String = asJava.toString
}
