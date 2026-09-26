package io.github.libtmux.scaladsl.cats

import io.github.libtmux.batch.{BatchResult, Batch => JavaBatch}
import scala.jdk.CollectionConverters._

/** An immutable batch plan. Construction does no tmux I/O and never touches a
  * shared Java object: each `add` returns a new value holding one more argument
  * vector. Each `run`/`length` builds a fresh Java `Batch` from `create` and
  * dispatches it through `Execution`, so it is F-suspended, cancellation-safe,
  * and admission-bounded like every other Cats operation — cancelling one run
  * never disturbs a concurrent one, since neither shares Java state.
  */
final class Batch[F[_]] private[cats] (
    create: () => JavaBatch,
    execution: Execution[F],
    operations: Vector[Vector[String]] = Vector.empty
) {
  def add(argv: Seq[String]): Batch[F] =
    new Batch(create, execution, operations :+ argv.toVector)

  def add(command: String, arguments: String*): Batch[F] =
    add(command +: arguments)

  def size: Int = operations.size
  def length: F[Int] = execution(build().length())
  def run: F[BatchResult] = execution(build().run())

  private def build(): JavaBatch = {
    val batch = create()
    operations.foreach(argv => batch.add(argv.asJava))
    batch
  }

  override def toString: String = s"Batch(operations=$size)"
}
