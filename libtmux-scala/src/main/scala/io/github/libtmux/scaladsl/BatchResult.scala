package io.github.libtmux.scaladsl

import io.github.libtmux.batch.{
  BatchResult => JavaBatchResult,
  OperationOutcome,
  OperationResult => JavaOperationResult
}
import scala.jdk.CollectionConverters._

/** Java's marker-based report. A parse rejection can label the first command
  * FAILED even when a later command caused the rejection and nothing ran.
  */
final class BatchResult private (
    private[scaladsl] val asJava: JavaBatchResult
) {
  def unsafeJava: JavaBatchResult = asJava
  val operations: Vector[OperationResult] =
    asJava.operations().asScala.iterator.map(new OperationResult(_)).toVector
  val succeeded: Boolean = asJava.succeeded()

  /** The operation Java labelled FAILED; this does not prove its location. */
  val reportedFailure: Option[OperationResult] =
    operations.find(_.reportedOutcome == OperationOutcome.FAILED)

  override def toString: String = asJava.toString
}

object BatchResult {
  private[scaladsl] def fromJava(value: JavaBatchResult): BatchResult =
    new BatchResult(value)
}

/** Raw operation attribution with strict Scala output collections. */
final class OperationResult private[scaladsl] (
    private[scaladsl] val asJava: JavaOperationResult
) {
  def unsafeJava: JavaOperationResult = asJava
  val argv: Vector[String] = asJava.argv().asScala.toVector
  val stdout: Vector[String] = asJava.stdout().asScala.toVector
  val stderr: Vector[String] = asJava.stderr().asScala.toVector
  val reportedOutcome: OperationOutcome = asJava.outcome()
  override def toString: String = asJava.toString
}
