package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import io.github.libtmux.ServerConfig
import io.github.libtmux.SessionId
import io.github.libtmux.control.{ControlEndedException, Delivery}
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import munit.FunSuite
import scala.concurrent.duration._

final class ObservationSuite extends FunSuite {
  test("kept returns an event and fails a gap") {
    assertEquals(Observation.kept(new Delivery.Event("pane")), "pane")
    val failure = intercept[IllegalStateException] {
      Observation.kept(new Delivery.Gap[String](2L))
    }
    assert(failure.getMessage.contains("2"))
  }

  test("a stream fails when the client ended the subscription") {
    val directory = Files.createTempDirectory("libtmux-observation")
    val fake = directory.resolve("tmux")
    Files.writeString(
      fake,
      """#!/bin/sh
        |printf '%%begin 100 1 0\n%%end 100 1 0\n'
        |IFS= read -r request
        |printf '%%begin 101 1 0\n%%end 101 1 0\n'
        |sleep 0.4
        |""".stripMargin
    )
    Files.setPosixFilePermissions(
      fake,
      PosixFilePermissions.fromString("rwx------")
    )
    val config = ServerConfig.builder().binary(fake.toString).build()
    val program = Control
      .attach[IO](config, new SessionId("$0"))
      .use(control => control.output(1).use(_.stream.compile.drain))
    val ran =
      try program.timeout(3.seconds).attempt.unsafeRunSync()
      finally {
        Files.deleteIfExists(fake)
        Files.deleteIfExists(directory)
      }
    assert(ran.left.exists(_.isInstanceOf[ControlEndedException]), ran)
  }
}
