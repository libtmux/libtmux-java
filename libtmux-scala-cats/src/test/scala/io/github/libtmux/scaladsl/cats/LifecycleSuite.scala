package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.{Deferred, IO}
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all._
import io.github.libtmux.{Server => JavaServer, ServerConfig}
import io.github.libtmux.transport.{
  CommandRequest,
  CommandResult,
  DispatchOutcome,
  TmuxTransport,
  TmuxTransportException
}
import java.util.List
import java.time.Duration
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import scala.concurrent.duration._

final class LifecycleSuite extends FunSuite {
  private val config = ServerConfig.builder().build()
  private def event(value: CompletableFuture[Unit]): IO[Unit] =
    IO.fromCompletableFuture(IO.pure(value))

  private final class Transport(holdInterrupted: Boolean = false)
      extends TmuxTransport {
    val calls = new AtomicInteger()
    val closes = new AtomicInteger()
    val entered = new CompletableFuture[Unit]()
    val interrupted = new CompletableFuture[Unit]()
    val finished = new CompletableFuture[Unit]()
    val release = new CountDownLatch(1)
    val settle = new CountDownLatch(1)
    val cause = new IllegalArgumentException("preserve this cause")
    val failure = new TmuxTransportException(
      "preserve this failure",
      DispatchOutcome.UNKNOWN,
      cause
    )
    @volatile var thread = ""

    override def execute(request: CommandRequest): CommandResult = {
      calls.incrementAndGet()
      thread = Thread.currentThread().getName
      request.commands().get(0).get(0) match {
        case "block" =>
          entered.complete(())
          try {
            if (!release.await(1, TimeUnit.SECONDS))
              throw new AssertionError("blocking producer was not interrupted")
          } catch {
            case problem: InterruptedException =>
              interrupted.complete(())
              if (holdInterrupted && !settle.await(1, TimeUnit.SECONDS))
                throw new AssertionError("cleanup was not released")
              finished.complete(())
              throw problem
          }
        case "unknown-block" =>
          entered.complete(())
          try {
            if (!release.await(1, TimeUnit.SECONDS))
              throw new AssertionError("blocking producer was not interrupted")
          } catch {
            case _: InterruptedException =>
              interrupted.complete(())
              Thread.currentThread().interrupt()
              throw failure
          }
        case "fail"    => throw failure
        case "nonzero" =>
          return new CommandResult(17, List.of("partial"), List.of("failure"))
        case _ => ()
      }
      new CommandResult(0, List.of(calls.get().toString), List.of())
    }
    override def close(): Unit = { closes.incrementAndGet(); () }
  }

  test(
    "effects are lazy, repeat work, preserve errors and leave borrowed Java open"
  ) {
    val transport = new Transport
    val java = JavaServer.using(config, transport)
    val program = Server
      .fromJava[IO](java)
      .use { server =>
        val command = server.cmd(Vector("read"))
        for {
          _ <- IO(assertEquals(transport.calls.get(), 0))
          first <- command
          second <- command
          _ <- IO {
            assertEquals(first.stdout, Vector("1"))
            assertEquals(second.stdout, Vector("2"))
            assert(
              transport.thread.startsWith("io-compute-blocker-") ||
                transport.thread.startsWith("io-blocking-"),
              transport.thread
            )
          }
          failed <- server.cmd(Vector("fail")).attempt
          nonzero <- server.cmd(Vector("nonzero"))
          _ <- IO {
            assert(failed.left.toOption.exists(_ eq transport.failure))
            assertEquals(transport.failure.outcome(), DispatchOutcome.UNKNOWN)
            assert(transport.failure.getCause eq transport.cause)
            assertEquals(nonzero.exitCode, 17)
            assertEquals(nonzero.stdout, Vector("partial"))
            assertEquals(nonzero.stderr, Vector("failure"))
          }
        } yield command
      }
      .flatMap { escaped =>
        for {
          failed <- escaped.attempt
          _ <- IO {
            assert(failed.isLeft)
            assertEquals(transport.closes.get(), 0)
            assertEquals(java.cmd("read").exitCode(), 0)
          }
        } yield ()
      }
    program.timeout(1.second).unsafeToFuture()
  }

  test("canceling a dispatched call keeps an unknown outcome") {
    val transport = new Transport
    val java = JavaServer.using(config, transport)
    val program = Server.fromJava[IO](java).use { server =>
      for {
        running <- server.cmd(Vector("unknown-block")).start
        _ <- event(transport.entered)
        _ <- running.cancel
        _ <- event(transport.interrupted)
        outcome <- running.join
        embedded <- outcome
          .embed(IO.raiseError(new RuntimeException("canceled")))
          .attempt
        _ <- IO(assert(embedded.left.toOption.exists(_ eq transport.failure)))
      } yield ()
    }
    program.timeout(2.seconds).unsafeToFuture()
  }

  test(
    "canceling queued and dispatched calls restores admission for siblings"
  ) {
    val transport = new Transport
    val java = JavaServer.using(config, transport)
    Server
      .fromJava[IO](java, maxConcurrentCalls = 1)
      .use { server =>
        for {
          first <- server.cmd(Vector("block")).start
          _ <- event(transport.entered)
          queuedStarted <- Deferred[IO, Unit]
          queued <- (queuedStarted.complete(()) *> server.cmd(
            Vector("queued")
          )).start
          _ <- queuedStarted.get
          _ <- queued.cancel
          _ <- IO(assertEquals(transport.calls.get(), 1))
          _ <- first.cancel
          _ <- event(transport.interrupted)
          firstOutcome <- first.join
          queuedOutcome <- queued.join
          sibling <- server.cmd(Vector("sibling"))
          _ <- IO {
            assert(firstOutcome.isCanceled)
            assert(queuedOutcome.isCanceled)
            assertEquals(sibling.stdout, Vector("2"))
          }
        } yield ()
      }
      .timeout(1.second)
      .unsafeToFuture()
  }

  test(
    "resource release cancels escaped operations before closing the facade"
  ) {
    val transport = new Transport
    val java = JavaServer.using(config, transport)
    Server
      .fromJava[IO](java)
      .use { server =>
        for {
          fiber <- server.cmd(Vector("block")).start
          _ <- event(transport.entered)
        } yield fiber
      }
      .flatMap { fiber =>
        for {
          _ <- event(transport.interrupted)
          outcome <- fiber.join
          _ <- IO {
            assert(outcome.isCanceled)
            assertEquals(transport.closes.get(), 0)
            assertEquals(java.cmd("after-release").exitCode(), 0)
          }
        } yield ()
      }
      .timeout(1.second)
      .unsafeToFuture()
  }

  test("capacity one rejects shared waits before Java dispatch") {
    val transport = new Transport
    val java = JavaServer.using(config, transport)
    Server
      .fromJava[IO](java, maxConcurrentCalls = 1)
      .use { server =>
        server.channel("capacity").await(Duration.ofMillis(500)).attempt.map {
          outcome =>
            assert(
              outcome.left.toOption.exists(_.getMessage.contains("capacity"))
            )
            assertEquals(transport.calls.get(), 0)
        }
      }
      .timeout(1.second)
      .unsafeToFuture()
  }

  test("failed acquisition is lazy and leaves a borrowed owner usable") {
    val transport = new Transport
    val java = JavaServer.using(config, transport)
    val borrowed = Server.fromJava[IO](java, maxConcurrentCalls = 0)
    val owned = Server.resource[IO](config, maxConcurrentCalls = 5)
    val program = for {
      _ <- IO(assertEquals(transport.calls.get(), 0))
      borrowedFailure <- borrowed.use(_ => IO.unit).attempt
      ownedFailure <- owned.use(_ => IO.unit).attempt
      _ <- IO {
        assert(
          borrowedFailure.left.toOption.exists(
            _.isInstanceOf[IllegalArgumentException]
          )
        )
        assert(
          ownedFailure.left.toOption.exists(
            _.isInstanceOf[IllegalArgumentException]
          )
        )
        assertEquals(transport.closes.get(), 0)
        assertEquals(java.cmd("after-failed-acquisition").exitCode(), 0)
      }
    } yield ()
    program.timeout(1.second).unsafeToFuture()
  }

  test("failed use cancels running calls before propagating its cause") {
    val transport = new Transport
    val java = JavaServer.using(config, transport)
    val expected = new IllegalStateException("failed body")
    Server
      .fromJava[IO](java)
      .use { server =>
        for {
          _ <- server.cmd(Vector("block")).start
          _ <- event(transport.entered)
          _ <- IO.raiseError[Unit](expected)
        } yield ()
      }
      .attempt
      .flatMap { result =>
        IO {
          assert(result.left.toOption.exists(_ eq expected))
          assert(transport.finished.isDone)
          assertEquals(transport.closes.get(), 0)
        }
      }
      .timeout(1.second)
      .unsafeToFuture()
  }

  test(
    "cancellation during finalization waits for interrupted work to finish"
  ) {
    val transport = new Transport(holdInterrupted = true)
    val java = JavaServer.using(config, transport)
    Server
      .fromJava[IO](java)
      .allocated
      .flatMap { case (server, close) =>
        val program = for {
          operation <- server.cmd(Vector("block")).start
          _ <- event(transport.entered)
          finalizer <- close.start
          _ <- event(transport.interrupted)
          cancelFinalizer <- finalizer.cancel.start
          rejected <- server.cmd(Vector("after-close-started")).attempt
          _ <- IO {
            assert(rejected.isLeft)
            assertEquals(transport.calls.get(), 1)
            assert(!transport.finished.isDone)
            transport.settle.countDown()
          }
          _ <- cancelFinalizer.joinWithNever
          _ <- finalizer.join
          outcome <- operation.join
          _ <- IO {
            assert(outcome.isCanceled)
            assert(transport.finished.isDone)
            assertEquals(transport.closes.get(), 0)
          }
        } yield ()
        program.guarantee(IO(transport.settle.countDown()) *> close)
      }
      .timeout(1.second)
      .unsafeToFuture()
  }

  test(
    "a caller masking cancellation can finish when its server scope closes"
  ) {
    val transport = new Transport
    val java = JavaServer.using(config, transport)
    Server
      .fromJava[IO](java)
      .allocated
      .flatMap { case (server, close) =>
        val program = for {
          operation <- IO
            .uncancelable(_ => server.cmd(Vector("block")).attempt)
            .start
          _ <- event(transport.entered)
          _ <- close
          outcome <- operation.join
          _ <- outcome match {
            case _root_.cats.effect.Outcome.Succeeded(result) =>
              result.map(value =>
                assert(
                  value.left.toOption.exists(
                    _.getMessage.contains("scope is closed")
                  )
                )
              )
            case other =>
              IO(fail("masked caller did not receive closure: " + other))
          }
        } yield ()
        program.guarantee(close)
      }
      .timeout(1.second)
      .unsafeToFuture()
  }
}
