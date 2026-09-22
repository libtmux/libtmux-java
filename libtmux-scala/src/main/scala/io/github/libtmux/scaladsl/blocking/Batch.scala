package io.github.libtmux.scaladsl.blocking

import io.github.libtmux.batch.{Batch => JavaBatch}
import io.github.libtmux.scaladsl.BatchResult
import scala.jdk.CollectionConverters._

/** A mutable Java command group. Arguments select targets; a pane-created batch
  * additionally retains that pane's captured server-incarnation guard.
  */
final class Batch private[blocking] (
    private[scaladsl] val asJava: JavaBatch,
    server: Server
) {
  def unsafeJava: JavaBatch = asJava
  def add(argv: Seq[String]): Batch = server.checked {
    asJava.add(argv.toVector.asJava)
    this
  }

  def add(command: String, arguments: String*): Batch =
    add(command +: arguments)

  def size: Int = asJava.size()
  def length: Int = asJava.length()

  /** Executes the group through the original Java client. Cancellation may
    * leave partial effects; Java's reported failure index can be unproven.
    */
  def run(): BatchResult = server.checked(BatchResult.fromJava(asJava.run()))

  override def toString: String = s"Batch(operations=$size)"
}
