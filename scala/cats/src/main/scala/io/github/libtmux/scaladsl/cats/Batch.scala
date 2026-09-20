package io.github.libtmux.scaladsl.cats

import io.github.libtmux.scaladsl.{BatchResult, blocking}

/** An immutable batch plan. Each run builds a fresh Java group in the server
  * scope; Java owns encoding, interruption and outcome attribution.
  */
final class Batch[F[_]] private[cats] (
    create: () => blocking.Batch,
    execution: Execution[F],
    operations: Vector[Vector[String]] = Vector.empty
) {
  def add(argv: Seq[String]): Batch[F] =
    new Batch(create, execution, operations :+ argv.toVector)

  def add(command: String, arguments: String*): Batch[F] =
    add(command +: arguments)

  def size: Int = operations.size
  def length: F[Int] = execution(build().length)
  def run: F[BatchResult] = execution(build().run())

  private def build(): blocking.Batch = {
    val batch = create()
    operations.foreach(batch.add)
    batch
  }

  override def toString: String = s"Batch(operations=$size)"
}
