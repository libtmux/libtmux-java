package io.github.libtmux.scaladsl.cats

import io.github.libtmux.exception.ControlEndedException
import _root_.cats.effect.{Deferred, IO}
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all._
import io.github.libtmux.ServerConfig
import io.github.libtmux.SessionId
import io.github.libtmux.control.{ControlClient, Delivery, PaneOutput}
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
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
      "#!/bin/sh\n" + ObservationSuite.prelude + """printf '%%begin 100 1 0\n%%end 100 1 0\n'
        |read_request
        |answer
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

  test(
    "canceling a stream blocked on the next event returns promptly and closes the subscription"
  ) {
    val directory = Files.createTempDirectory("libtmux-observation-cancel")
    val fake = directory.resolve("tmux")
    Files.writeString(
      fake,
      "#!/bin/sh\n" + ObservationSuite.prelude +
        // One event answers the "is the pipeline alive" question, then the fake
        // goes silent for far longer than this test runs: the next read has
        // nothing to consume until this test cancels it.
        """printf '%%begin 100 1 0\n%%end 100 1 0\n'
          |read_request
          |answer
          |read_request
          |printf '%%output %%1 hi\n'
          |answer
          |sleep 30
          |""".stripMargin
    )
    Files.setPosixFilePermissions(
      fake,
      PosixFilePermissions.fromString("rwx------")
    )
    val config = ServerConfig.builder().binary(fake.toString).build()
    val client = ControlClient.attachUnfenced(config, new SessionId("$0"))
    val subscription = client.subscribeOutput(1)
    val program = Observation
      .resource[IO, PaneOutput](subscription, new AtomicBoolean(false))
      .use { observation =>
        for {
          // A ping that only the fake answers after writing the one event proves
          // the subscription existed before that event was offered, so nothing
          // was lost to the registration race.
          firstSeen <- Deferred[IO, Unit]
          fiber <- observation.stream
            .evalTap(_ => firstSeen.complete(()).void)
            .compile
            .drain
            .start
          _ <- IO.blocking(client.send("ping")).timeout(1.second)
          _ <- firstSeen.get.timeout(1.second)
          // The one event is read; the fake sends no more. A second `next()`
          // call has nowhere to return from except this test's cancellation.
          stillBlocked <- IO
            .race(fiber.join, IO.sleep(300.millis))
            .map(_.isRight)
          _ <- IO(assert(stillBlocked, "expected the read to still be blocked"))
          outcome <- (fiber.cancel *> fiber.join).timeout(500.millis)
          _ <- IO(assert(outcome.isCanceled, outcome.toString))
        } yield ()
      }
    val ran =
      try program.timeout(3.seconds).attempt.unsafeRunSync()
      finally {
        client.close()
        Files.deleteIfExists(fake)
        Files.deleteIfExists(directory)
      }
    assert(ran.isRight, ran)
    assert(
      subscription.isClosed(),
      "expected resource release to close the subscription"
    )
  }

  test("a watch reports liveness and a normalized target") {
    val directory = Files.createTempDirectory("libtmux-watch")
    val fake = directory.resolve("tmux")
    val seen = directory.resolve("seen")
    Files.writeString(
      fake,
      "#!/bin/sh\n" + ObservationSuite.prelude + """dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
        |printf '%%begin 100 1 0\n%%end 100 1 0\n'
        |read_request
        |answer
        |i=0
        |while read_request; do
        |  printf '%s\n' "$request" >> "$dir/seen"
        |  answer
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
      "#!/bin/sh\n" + ObservationSuite.prelude + """printf 'boom\n' >&2
        |dd if=/dev/zero bs=5000 count=1 2>/dev/null | tr '\0' x >&2
        |printf '%%begin 100 1 0\n%%end 100 1 0\n'
        |read_request
        |answer
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

object ObservationSuite {

  /** What every fake starts with. tmux follows each request line with a marker
    * line, a `display-message -p` of a token, and a reply ends with the
    * marker's block: `read_request` reads a request and its marker, and
    * `answer` writes a reply block, flagged as this client's command, then the
    * marker's block.
    */
  val prelude: String =
    """|read_request() { IFS= read -r request && IFS= read -r marker; }
       |answer() {
       |  printf '%%begin 1 1 1\n'
       |  for line in "$@"; do printf '%s\n' "$line"; done
       |  printf '%%end 1 1 1\n'
       |  token=${marker##*"' '"}
       |  printf '%%begin 1 2 1\n%s\n%%end 1 2 1\n' "${token%"'"}"
       |}
       |""".stripMargin
}
