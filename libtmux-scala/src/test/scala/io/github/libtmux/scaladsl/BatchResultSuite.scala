package io.github.libtmux.scaladsl

import io.github.libtmux.batch.{
  BatchResult => JavaBatchResult,
  OperationOutcome,
  OperationResult
}
import java.util.List
import munit.FunSuite
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/** `batch.BatchResult`/`OperationResult` are Java's own records, used directly
  * rather than wrapped, so this pins their attribution and failure lookup as a
  * Scala caller meets them.
  */
final class BatchResultSuite extends FunSuite {
  test("native rows retain raw attribution and do not invent a failure index") {
    val rejected = new OperationResult(
      List.of("display-message", "-p", "first"),
      OperationOutcome.FAILED,
      List.of("", "partial", ""),
      List.of("unknown command: later", "")
    )
    val unknown = new OperationResult(
      List.of("display-message", "-p", "later"),
      OperationOutcome.UNKNOWN,
      List.of("after"),
      List.of()
    )
    val result = new JavaBatchResult(List.of(rejected, unknown))
    assertEquals(
      result.operations().asScala.toVector,
      Vector(rejected, unknown)
    )
    assertEquals(
      result.operations().get(0).argv().asScala.toVector,
      Vector("display-message", "-p", "first")
    )
    assertEquals(
      result.operations().get(0).stdout().asScala.toVector,
      Vector("", "partial", "")
    )
    assertEquals(
      result.operations().get(0).stderr().asScala.toVector,
      Vector("unknown command: later", "")
    )
    assertEquals(
      result.operations().asScala.map(_.outcome()).toVector,
      Vector(OperationOutcome.FAILED, OperationOutcome.UNKNOWN)
    )
    assertEquals(result.failure().toScala, Some(rejected))
    assert(!result.succeeded())
    val empty = new JavaBatchResult(List.of())
    assert(empty.operations().isEmpty)
    assertEquals(empty.failure().toScala, None)
    assert(empty.succeeded())
  }
}
