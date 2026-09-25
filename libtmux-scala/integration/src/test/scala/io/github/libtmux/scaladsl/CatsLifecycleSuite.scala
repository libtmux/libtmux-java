package io.github.libtmux.scaladsl

import io.github.libtmux.exception.ServerClosedException
import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all._
import io.github.libtmux.{
  Server => JavaServer,
  SessionSpec,
  TextOutcome,
  WakeReason
}
import io.github.libtmux.scaladsl.cats.Server
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import io.github.libtmux.transport.{
  CommandRequest,
  CommandResult,
  DispatchOutcome,
  ProcessTransport,
  TmuxTransport
}
import java.time.Duration
import java.util.List
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import munit.FunSuite
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

final class CatsLifecycleSuite extends FunSuite {
  private val deadline = Duration.ofMillis(800)

  private def shellWord(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

  private final class SignaledTransport(delegate: ProcessTransport)
      extends TmuxTransport {
    val waits = new AtomicInteger()
    val closes = new AtomicInteger()
    val failure = new AtomicReference[Option[ServerClosedException]](None)
    override def execute(request: CommandRequest): CommandResult =
      delegate.execute(request)
    override def executeWaiting(request: CommandRequest): CommandResult = {
      val number = waits.incrementAndGet()
      val commands = List.of("wait-for", "-S", s"dispatched-$number") +:
        request.commands().asScala.toVector
      try {
        delegate.executeWaiting(
          new CommandRequest(
            request.endpoint(),
            commands.asJava,
            request.timeout(),
            request.input()
          )
        )
      } catch {
        case problem: ServerClosedException =>
          failure.set(Some(problem))
          throw problem
      }
    }
    override def close(): Unit = { closes.incrementAndGet(); delegate.close() }
  }

  test(
    "shared waits retain signal capacity and cancellation releases live clients"
  ) {
    OwnedTmux.use { fixture =>
      val transport =
        fixture.own(new SignaledTransport(new ProcessTransport(2)))
      val java = fixture.own(JavaServer.using(fixture.config, transport))
      val program = Server
        .fromJava[IO](java, maxConcurrentCalls = 2)
        .use { server =>
          for {
            first <- server.channel("first").await(deadline).start
            ready <- IO.interruptible(
              fixture.server.channel("dispatched-1").await(deadline)
            )
            _ <- IO(assertEquals(ready, WakeReason.SIGNALLED))
            queued <- server.channel("second").await(deadline).start
            _ <- server.channel("first").signal
            firstResult <- first.joinWithNever
            _ <- IO(assertEquals(firstResult, WakeReason.SIGNALLED))
            secondReady <- IO.interruptible(
              fixture.server.channel("dispatched-2").await(deadline)
            )
            _ <- IO(assertEquals(secondReady, WakeReason.SIGNALLED))
            _ <- queued.cancel
            canceled <- queued.join
            sessions <- server.sessions
            _ <- IO {
              assert(canceled.isCanceled)
              assert(sessions.nonEmpty)
            }
          } yield sessions.head
        }
        .flatMap { escaped =>
          for {
            closed <- escaped.refresh.attempt
            _ <- IO {
              assert(closed.isLeft)
              assertEquals(transport.closes.get(), 0)
              assert(java.isAlive())
            }
          } yield ()
        }
      program.timeout(1.second).unsafeRunSync()
    }
  }

  test("owned release cancels live escaped waits and preserves the daemon") {
    OwnedTmux.use { fixture =>
      Server
        .resource[IO](fixture.config)
        .use { server =>
          for {
            session <- server.newSession(
              SessionSpec
                .builder()
                .named("cats-owned-shell")
                .running("sh")
                .build()
            )
            pane = session.activePane.get
            _ <- pane.sendLine("printf '%s\\n' cats-owned-marker")
            captured <- pane.awaitText("cats-owned-marker", deadline)
            _ <- IO(
              assert(
                Set(TextOutcome.APPEARED, TextOutcome.PRESENT_AT_ENTRY)(
                  captured
                )
              )
            )
            fiber <- server
              .cmd(
                Vector(
                  "if-shell",
                  "-F",
                  "1",
                  "wait-for -S owned-escaped-ready ; wait-for owned-escaped"
                ),
                deadline
              )
              .start
            ready <- IO.interruptible(
              fixture.server.channel("owned-escaped-ready").await(deadline)
            )
            _ <- IO(assertEquals(ready, WakeReason.SIGNALLED))
          } yield fiber
        }
        .flatMap { fiber =>
          fiber.join.flatMap { outcome =>
            IO {
              assert(outcome.isCanceled)
              assert(fixture.server.isAlive())
            }
          }
        }
        .timeout(1.second)
        .unsafeRunSync()
    }
  }

  test("cancelling dispatched shell work does not roll back its daemon job") {
    OwnedTmux.use { fixture =>
      val command =
        fixture.config.endpointCommand().asScala.map(shellWord).mkString(" ")
      val script =
        s"$command wait-for -S cats-shell-started; " +
          s"$command wait-for cats-shell-release; " +
          s"$command wait-for -S cats-shell-completed"
      Server
        .resource[IO](fixture.config)
        .use { server =>
          for {
            operation <- server.runShell(script).start
            started <- IO.interruptible(
              fixture.server.channel("cats-shell-started").await(deadline)
            )
            _ <- IO(assertEquals(started, WakeReason.SIGNALLED))
            _ <- operation.cancel
            outcome <- operation.join
            _ <- IO(assert(outcome.isCanceled))
            _ <- IO.interruptible(
              fixture.server.channel("cats-shell-release").signal()
            )
            completed <- IO.interruptible(
              fixture.server.channel("cats-shell-completed").await(deadline)
            )
            sessions <- server.sessions
            _ <- IO {
              assertEquals(completed, WakeReason.SIGNALLED)
              assert(sessions.nonEmpty)
            }
          } yield ()
        }
        .timeout(1.second)
        .unsafeRunSync()
    }
  }

  test(
    "partial resource acquisition closes the client and preserves its cause"
  ) {
    OwnedTmux.use { fixture =>
      val acquired = new AtomicReference[Option[JavaServer]](None)
      val expected = new IllegalStateException("failed after acquisition")
      val resource = Server.resource[IO](fixture.config).evalMap { server =>
        IO(acquired.set(Some(server.asJava))) *> server.sessions *>
          IO.raiseError[Unit](expected)
      }
      val result =
        resource.use(_ => IO.unit).attempt.timeout(1.second).unsafeRunSync()
      assert(result.left.toOption.exists(_ eq expected))
      val java = acquired.get().get
      intercept[IllegalStateException](java.cmd("list-sessions"))
      assert(fixture.server.isAlive())
    }
  }

  test(
    "a borrowed owner closing a dispatched wait preserves its Java failure"
  ) {
    OwnedTmux.use { fixture =>
      val transport =
        fixture.own(new SignaledTransport(new ProcessTransport(2)))
      val java = fixture.own(JavaServer.using(fixture.config, transport))
      Server
        .fromJava[IO](java, maxConcurrentCalls = 2)
        .use { server =>
          for {
            operation <- server
              .channel("owner-closes")
              .await(deadline)
              .attempt
              .start
            ready <- IO.interruptible(
              fixture.server.channel("dispatched-1").await(deadline)
            )
            _ <- IO(assertEquals(ready, WakeReason.SIGNALLED))
            _ <- IO.blocking(transport.close())
            result <- operation.joinWithNever
            independent <- IO.interruptible(fixture.server.sessions())
            _ <- IO {
              val javaFailure = transport.failure.get().get
              assert(result.left.toOption.exists(_ eq javaFailure))
              assertEquals(javaFailure.outcome(), DispatchOutcome.UNKNOWN)
              assert(!independent.isEmpty)
              assertEquals(transport.closes.get(), 1)
            }
          } yield ()
        }
        .timeout(1.second)
        .unsafeRunSync()
      assertEquals(transport.closes.get(), 1)
    }
  }
}
