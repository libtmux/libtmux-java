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
      .attachUnfenced[IO](config, new SessionId("$0"))
      .use(control => control.output(1).use(_.stream.compile.drain))
    val ran =
      try program.timeout(3.seconds).attempt.unsafeRunSync()
      finally {
        Files.deleteIfExists(fake)
        Files.deleteIfExists(directory)
      }
    assert(ran.left.exists(_.isInstanceOf[ControlEndedException]), ran)
  }

  test("a watch reports liveness and a normalized target") {
    val directory = Files.createTempDirectory("libtmux-watch")
    val fake = directory.resolve("tmux")
    val seen = directory.resolve("seen")
    Files.writeString(
      fake,
      """#!/bin/sh
        |dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
        |printf '%%begin 100 1 0\n%%end 100 1 0\n'
        |IFS= read -r request
        |printf '%%begin 101 1 0\n%%end 101 1 0\n'
        |i=0
        |while IFS= read -r request; do
        |  printf '%s\n' "$request" >> "$dir/seen"
        |  printf '%%begin 101 1 0\n%%end 101 1 0\n'
        |  i=$((i + 1))
        |  if [ "$i" -ge 3 ]; then
        |    exit 0
        |  fi
        |done
        |""".stripMargin
    )
    Files.setPosixFilePermissions(
      fake,
      PosixFilePermissions.fromString("rwx------")
    )
    val config = ServerConfig.builder().binary(fake.toString).build()
    val program =
      Control.attachUnfenced[IO](config, new SessionId("$0")).use { control =>
        for {
          alive <- control.isAlive
          watched <- control.watch("cmd", "%1", "#{pane_current_command}")
          session <- control.watch("sess", "not-a-pane", "#{session_name}")
          stopped <- control.unwatch("cmd")
          _ <- IO.sleep(300.millis)
          ended <- control.isAlive
          _ <- IO {
            assert(alive)
            assert(watched.accepted)
            assert(session.accepted)
            assert(stopped.accepted)
            assert(!ended)
          }
        } yield ()
      }
    val lines =
      try {
        program.timeout(5.seconds).unsafeRunSync()
        Files.readString(seen).linesIterator.toVector
      } finally {
        Files.deleteIfExists(fake)
        Files.deleteIfExists(seen)
        Files.deleteIfExists(directory)
      }
    assert(lines.exists(_.contains("cmd:%1:#{pane_current_command}")))
    assert(lines.exists(_.contains("sess::#{session_name}")))
    assert(
      lines.exists(line => line.contains("'-B'") && line.endsWith("'cmd'"))
    )
  }

  test("stderr stays bounded") {
    val directory = Files.createTempDirectory("libtmux-stderr")
    val fake = directory.resolve("tmux")
    Files.writeString(
      fake,
      """#!/bin/sh
        |printf 'boom\n' >&2
        |dd if=/dev/zero bs=5000 count=1 2>/dev/null | tr '\0' x >&2
        |printf '%%begin 100 1 0\n%%end 100 1 0\n'
        |IFS= read -r request
        |printf '%%begin 101 1 0\n%%end 101 1 0\n'
        |sleep 1
        |""".stripMargin
    )
    Files.setPosixFilePermissions(
      fake,
      PosixFilePermissions.fromString("rwx------")
    )
    val config = ServerConfig.builder().binary(fake.toString).build()
    val program =
      Control.attachUnfenced[IO](config, new SessionId("$0")).use { control =>
        for {
          _ <- IO.sleep(200.millis)
          text <- control.standardError
          cut <- control.standardErrorTruncated
          _ <- IO {
            assert(text.startsWith("boom"))
            assert(text.length <= 4096)
            assert(cut)
          }
        } yield ()
      }
    try program.timeout(5.seconds).unsafeRunSync()
    finally {
      Files.deleteIfExists(fake)
      Files.deleteIfExists(directory)
    }
  }
}
