package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.Async

/** Small final classes wrapping one of the tmux subsystems (`Server.hooks()`,
  * `Pane.options()`, ...): `(underlying, server)`, as `Handles.scala`'s classes
  * are, but holding the raw Java handle directly rather than a `direct.X`
  * opaque type. There is no direct-style counterpart to go through: direct
  * style is blocking by design and stays on the Java handle these operations
  * already reached. Every operation beyond equality is an extension, generated
  * from the operation catalog.
  */
final class Hooks[F[_]] private[cats] (
    private[cats] val underlying: io.github.libtmux.Hooks,
    val server: Server[F]
)(implicit F: Async[F]) {
  override def equals(other: Any): Boolean = other match {
    case that: Hooks[?] => underlying.equals(that.underlying)
    case _              => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Hooks = underlying
}

final class Options[F[_]] private[cats] (
    private[cats] val underlying: io.github.libtmux.Options,
    val server: Server[F]
)(implicit F: Async[F]) {
  override def equals(other: Any): Boolean = other match {
    case that: Options[?] => underlying.equals(that.underlying)
    case _                => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Options = underlying
}

object Options {

  /** `get`/`set` sit in one overload set beside the generic `OptionKey<T>`
    * overload of the same name the catalog-generated forwards cannot mirror
    * (see `ScalaOperationGenerator.HANDWRITTEN_OVERRIDES`): two separate
    * `extension` clauses for the same name compete in Scala 3's extension
    * search rather than complementing each other, so both overloads of each are
    * handwritten together here.
    */
  extension [F[_]](self: Options[F])(using F: Async[F]) {

    /** The value in effect at this scope, inherited from a parent scope when
      * this one does not set it.
      */
    def get(name: String): F[Option[String]] =
      self.server.execution(
        _root_.scala.jdk.OptionConverters
          .RichOptional(self.underlying.get(name))
          .toScala
      )

    /** As `get(String)`, read as the key's type. */
    def get[T](key: io.github.libtmux.OptionKey[T]): F[Option[T]] =
      self.server.execution(
        _root_.scala.jdk.OptionConverters
          .RichOptional(self.underlying.get(key))
          .toScala
      )

    /** Sets one option at this scope. */
    def set(name: String, value: String): F[Unit] =
      self.server.execution(self.underlying.set(name, value))

    /** As `set(String, String)`, written the way tmux reads the key's type. */
    def set[T](key: io.github.libtmux.OptionKey[T], value: T): F[Unit] =
      self.server.execution(self.underlying.set(key, value))
  }
}

final class Shell[F[_]] private[cats] (
    private[cats] val underlying: io.github.libtmux.Shell,
    val server: Server[F]
)(implicit F: Async[F]) {
  override def equals(other: Any): Boolean = other match {
    case that: Shell[?] => underlying.equals(that.underlying)
    case _              => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Shell = underlying
}

final class Commands[F[_]] private[cats] (
    private[cats] val underlying: io.github.libtmux.Commands,
    val server: Server[F]
)(implicit F: Async[F]) {
  override def equals(other: Any): Boolean = other match {
    case that: Commands[?] => underlying.equals(that.underlying)
    case _                 => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Commands = underlying
}

final class Buffers[F[_]] private[cats] (
    private[cats] val underlying: io.github.libtmux.Buffers,
    val server: Server[F]
)(implicit F: Async[F]) {
  override def equals(other: Any): Boolean = other match {
    case that: Buffers[?] => underlying.equals(that.underlying)
    case _                => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Buffers = underlying
}

final class Environment[F[_]] private[cats] (
    private[cats] val underlying: io.github.libtmux.Environment,
    val server: Server[F]
)(implicit F: Async[F]) {
  override def equals(other: Any): Boolean = other match {
    case that: Environment[?] => underlying.equals(that.underlying)
    case _                    => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Environment = underlying
}

final class MessageLog[F[_]] private[cats] (
    private[cats] val underlying: io.github.libtmux.MessageLog,
    val server: Server[F]
)(implicit F: Async[F]) {
  override def equals(other: Any): Boolean = other match {
    case that: MessageLog[?] => underlying.equals(that.underlying)
    case _                   => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.MessageLog = underlying
}

final class Prompt[F[_]] private[cats] (
    private[cats] val underlying: io.github.libtmux.Prompt,
    val server: Server[F]
)(implicit F: Async[F]) {
  override def equals(other: Any): Boolean = other match {
    case that: Prompt[?] => underlying.equals(that.underlying)
    case _               => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Prompt = underlying
}

final class Keys[F[_]] private[cats] (
    private[cats] val underlying: io.github.libtmux.Keys,
    val server: Server[F]
)(implicit F: Async[F]) {
  override def equals(other: Any): Boolean = other match {
    case that: Keys[?] => underlying.equals(that.underlying)
    case _             => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Keys = underlying
}
