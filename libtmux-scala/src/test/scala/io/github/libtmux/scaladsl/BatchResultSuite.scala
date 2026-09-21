package io.github.libtmux.scaladsl

import io.github.libtmux.batch.{
  BatchResult => JavaBatchResult,
  OperationOutcome,
  OperationResult => JavaOperationResult
}
import java.util.List
import munit.FunSuite

final class BatchResultSuite extends FunSuite {
  test("native rows retain raw attribution and do not invent a failure index") {
    val rejected = new JavaOperationResult(
      List.of("display-message", "-p", "first"),
      OperationOutcome.FAILED,
      List.of("", "partial", ""),
      List.of("unknown command: later", "")
    )
    val unknown = new JavaOperationResult(
      List.of("display-message", "-p", "later"),
      OperationOutcome.UNKNOWN,
      List.of("after"),
      List.of()
    )
    val java = new JavaBatchResult(List.of(rejected, unknown))
    val result = BatchResult.fromJava(java)
    assert(result.asJava eq java)
    assertEquals(result.operations.map(_.asJava), Vector(rejected, unknown))
    assertEquals(
      result.operations.head.argv,
      Vector("display-message", "-p", "first")
    )
    assertEquals(result.operations.head.stdout, Vector("", "partial", ""))
    assertEquals(
      result.operations.head.stderr,
      Vector("unknown command: later", "")
    )
    assertEquals(
      result.operations.map(_.reportedOutcome),
      Vector(OperationOutcome.FAILED, OperationOutcome.UNKNOWN)
    )
    assertEquals(result.reportedFailure.map(_.asJava), Some(rejected))
    assert(!result.succeeded)
    val empty = BatchResult.fromJava(new JavaBatchResult(List.of()))
    assert(empty.operations.isEmpty)
    assertEquals(empty.reportedFailure, None)
    assert(empty.succeeded)
  }
}
