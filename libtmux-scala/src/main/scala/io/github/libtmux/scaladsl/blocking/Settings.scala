package io.github.libtmux.scaladsl.blocking

import io.github.libtmux.{
  BufferInfo,
  Buffers => JavaBuffers,
  Environment => JavaEnvironment,
  Hooks => JavaHooks,
  OptionKey,
  Options => JavaOptions
}
import java.nio.file.Path
import scala.collection.immutable.VectorMap
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/** Blocking option operations at one Java scope. `unsafeJava` borrows that
  * scope.
  */
final class Options private[blocking] (
    private[scaladsl] val asJava: JavaOptions,
    owner: Server
) {
  def unsafeJava: JavaOptions = asJava

  /** Reads the effective value, retaining a present empty string. */
  def get(name: String): Option[String] = owner.checked {
    asJava.get(name).toScala
  }

  def get[A](key: OptionKey[A]): Option[A] = owner.checked {
    asJava.get(key).toScala
  }

  def set(name: String, value: String): Unit = owner.checked {
    asJava.set(name, value)
  }

  def set[A](key: OptionKey[A], value: A): Unit = owner.checked {
    asJava.set(key, value)
  }

  /** Captures values set at this scope, in tmux order. */
  def all(): VectorMap[String, String] = owner.checked {
    VectorMap.from(asJava.all().asScala)
  }

  /** Captures tmux's wide listing. Inherited user-option names are absent;
    * `get` still resolves them by name.
    */
  def effective(): VectorMap[String, String] = owner.checked {
    VectorMap.from(asJava.effective().asScala)
  }

  def setIfAbsent(name: String, value: String): Boolean = owner.checked {
    asJava.setIfAbsent(name, value)
  }

  def append(name: String, suffix: String): Unit = owner.checked {
    asJava.append(name, suffix)
  }

  def setExpanded(name: String, format: String): Unit = owner.checked {
    asJava.setExpanded(name, format)
  }

  def unset(name: String): Unit = owner.checked {
    asJava.unset(name)
  }
}

/** Blocking environment operations. Removed names remain distinct from absence.
  */
final class Environment private[blocking] (
    private[scaladsl] val asJava: JavaEnvironment,
    owner: Server
) {
  def unsafeJava: JavaEnvironment = asJava

  /** Reads a local value; removed and absent names both return None. */
  def get(name: String): Option[String] = owner.checked {
    asJava.get(name).toScala
  }

  def isRemoved(name: String): Boolean = owner.checked {
    asJava.isRemoved(name)
  }

  /** Captures values set at this scope, in tmux order. */
  def all(): VectorMap[String, String] = owner.checked {
    VectorMap.from(asJava.all().asScala)
  }

  /** Captures inherited values with local values and removal marks applied. */
  def effective(): VectorMap[String, String] = owner.checked {
    VectorMap.from(asJava.effective().asScala)
  }

  def removed(): Set[String] = owner.checked {
    asJava.removed().asScala.toSet
  }

  def set(name: String, value: String): Unit = owner.checked {
    asJava.set(name, value)
  }

  def setExpanded(name: String, format: String): Unit = owner.checked {
    asJava.setExpanded(name, format)
  }

  def unset(name: String): Unit = owner.checked {
    asJava.unset(name)
  }

  def remove(name: String): Unit = owner.checked {
    asJava.remove(name)
  }
}

/** Blocking hooks at one scope. Command text and argv remain separate
  * overloads.
  */
final class Hooks private[blocking] (
    private[scaladsl] val asJava: JavaHooks,
    owner: Server
) {
  def unsafeJava: JavaHooks = asJava

  def set(event: String, command: String): Unit = owner.checked {
    asJava.set(event, command)
  }

  def set(event: String, command: Seq[String]): Unit = owner.checked {
    asJava.set(event, command.asJava)
  }

  def append(event: String, command: String): Unit = owner.checked {
    asJava.append(event, command)
  }

  def append(event: String, command: Seq[String]): Unit = owner.checked {
    asJava.append(event, command.asJava)
  }

  def unset(event: String): Unit = owner.checked {
    asJava.unset(event)
  }

  def run(event: String): Unit = owner.checked {
    asJava.run(event)
  }

  /** Captures ordered commands; Java's listing does not retain sparse indices.
    */
  def all(): VectorMap[String, Vector[String]] = owner.checked {
    VectorMap.from(asJava.all().asScala.iterator.map { case (event, commands) =>
      event -> commands.asScala.toVector
    })
  }
}

/** Blocking named buffers with Java's normalized text and exact-delete guards.
  */
final class Buffers private[blocking] (
    private[scaladsl] val asJava: JavaBuffers,
    owner: Server
) {
  def unsafeJava: JavaBuffers = asJava

  def list(): Vector[BufferInfo] = owner.checked {
    asJava.list().asScala.toVector
  }

  def set(name: String, contents: String): Unit = owner.checked {
    asJava.set(name, contents)
  }

  /** Reads normalized text; trailing newlines and arbitrary bytes are not
    * exact.
    */
  def show(name: String): String = owner.checked {
    asJava.show(name)
  }

  /** Deletes an exact name; Java refuses unsupported tmux versions. */
  def delete(name: String): Unit = owner.checked {
    asJava.delete(name)
  }

  def load(name: String, file: Path): Unit = owner.checked {
    asJava.load(name, file)
  }

  def save(name: String, file: Path): Unit = owner.checked {
    asJava.save(name, file)
  }
}
