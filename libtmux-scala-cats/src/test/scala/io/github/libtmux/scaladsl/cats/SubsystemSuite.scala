package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import io.github.libtmux.exception.DispatchException
import io.github.libtmux.{Server => JavaServer, ServerConfig}
import io.github.libtmux.transport.{
  CommandRequest,
  CommandResult,
  DispatchOutcome,
  TmuxTransport
}
import java.util.List
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import munit.FunSuite
import scala.concurrent.duration._

/** Before subsystem owners (`Hooks`, `Options`, ...) were wrapped,
  * `server.hooks()` answered the raw Java `Hooks`, and `.all()` on it was a
  * plain blocking call: not `F[_]`, not admission-bounded, not cancellable.
  * This proves the fix at runtime, the same way `LifecycleSuite` proves it for
  * `Server.cmd`: a blocked subsystem call is a real fiber that cancellation
  * actually interrupts.
  */
final class SubsystemSuite extends FunSuite {
  private val config = ServerConfig.builder().build()

  private final class BlockingTransport extends TmuxTransport {
    val entered = new CompletableFuture[Unit]()
    val interrupted = new CompletableFuture[Unit]()
    val release = new CountDownLatch(1)
    val failure = new DispatchException.Failed(
      "interrupted",
      DispatchOutcome.UNKNOWN,
      new InterruptedException()
    )

    override def execute(request: CommandRequest): CommandResult = {
      entered.complete(())
      try {
        if (!release.await(2, TimeUnit.SECONDS))
          throw new AssertionError("blocking producer was not interrupted")
        new CommandResult(0, List.of(), List.of())
      } catch {
        case _: InterruptedException =>
          interrupted.complete(())
          throw failure
      }
    }
    override def close(): Unit = ()
  }

  private def event(value: CompletableFuture[Unit]): IO[Unit] =
    IO.fromCompletableFuture(IO.pure(value))

  test("Hooks.all(), reached through Server.hooks(), is F[_] and cancellable") {
    val transport = new BlockingTransport
    val java = JavaServer.using(config, transport)
    val program = Server.fromJava[IO](java).use { server =>
      for {
        running <- server.hooks.all().start
        _ <- event(transport.entered)
        _ <- running.cancel
        _ <- event(transport.interrupted)
        outcome <- running.join
        _ <- IO(assert(outcome.isCanceled, outcome.toString))
      } yield ()
    }
    program.timeout(2.seconds).unsafeToFuture()
  }
}
