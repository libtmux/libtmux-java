package io.github.libtmux.scaladsl.fixture

import _root_.cats.effect.{Deferred, IO, Outcome, Resource}
import _root_.cats.effect.unsafe.implicits.global
import io.github.libtmux.control.ControlClient
import java.nio.file.Files
import munit.FunSuite
import scala.jdk.CollectionConverters._
import scala.util.Using

final class OwnedTmuxSuite extends FunSuite {
  test("owned cleanup removes the daemon, socket, children and workers") {
    OwnedTmux.use { fixture =>
      assert(fixture.server.isAlive())
      val daemon = fixture.serverProcess
      val control = fixture.own(
        ControlClient.attach(
          fixture.config,
          fixture.server.sessions().get(0).id()
        )
      )
      val subscription = fixture.own(control.subscribeEvents(4))
      Files.writeString(
        Files
          .createDirectory(fixture.directory.resolve("files"))
          .resolve("buffer"),
        "owned"
      )
      fixture.close()
      fixture.close()
      assert(!daemon.isAlive)
      assert(!control.isAlive())
      assert(subscription.isClosed())
      assert(!Files.exists(fixture.directory))
    }
  }

  test("body and acquisition failures clean up without touching a sibling") {
    OwnedTmux.use { sibling =>
      def fixtureDirectories =
        Using.resource(Files.list(sibling.directory.getParent)) {
          _.iterator().asScala
            .filter(
              _.getFileName.toString
                .startsWith("scala-" + ProcessHandle.current().pid() + "-")
            )
            .toSet
        }
      val before = fixtureDirectories
      intercept[IllegalArgumentException](OwnedTmux.acquire("")(_ => ()))
      assertEquals(fixtureDirectories, before)
      var acquired: Option[OwnedTmux] = None
      val expected = new IllegalStateException("fixture acquisition failure")
      val failure = intercept[IllegalStateException] {
        OwnedTmux.acquire(OwnedTmux.binary) { fixture =>
          acquired = Some(fixture)
          throw expected
        }
      }
      assert(failure eq expected)
      assertEquals(failure.getSuppressed.toVector, Vector.empty)
      assert(!acquired.get.serverProcess.isAlive)
      assert(!Files.exists(acquired.get.directory))
      intercept[IllegalArgumentException] {
        OwnedTmux.use(_ =>
          throw new IllegalArgumentException("fixture body failure")
        )
      }
      assert(sibling.server.isAlive())
    }
  }

  test("cancellation waits for fixture finalization") {
    val program = for {
      acquired <- Deferred[IO, OwnedTmux]
      finalized <- Deferred[IO, Either[Throwable, Unit]]
      fiber <- Resource
        .make(IO.blocking(OwnedTmux.open())) { fixture =>
          IO.blocking(fixture.close()).attempt.flatMap { result =>
            finalized.complete(result) *> IO.fromEither(result)
          }
        }
        .use(fixture => acquired.complete(fixture) *> IO.never[Unit])
        .start
      fixture <- IO.race(acquired.get, fiber.join).flatMap {
        case Left(fixture)                   => IO.pure(fixture)
        case Right(Outcome.Errored(failure)) =>
          IO.raiseError[OwnedTmux](failure)
        case Right(outcome) =>
          IO.raiseError[OwnedTmux](
            new AssertionError("fixture ended before acquisition: " + outcome)
          )
      }
      _ <- fiber.cancel
      released <- finalized.get
      _ <- IO.fromEither(released)
      outcome <- fiber.join
      _ <- IO {
        assert(outcome.isCanceled)
        assert(!fixture.serverProcess.isAlive)
        assert(!Files.exists(fixture.directory))
      }
    } yield ()
    program.unsafeRunSync()
  }
}
