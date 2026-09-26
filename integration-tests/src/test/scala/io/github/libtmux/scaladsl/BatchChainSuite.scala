package io.github.libtmux.scaladsl

import io.github.libtmux.exception.{TargetGoneException, DispatchException}
import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all._
import io.github.libtmux.{Server => JavaServer, SessionSpec, WakeReason}
import io.github.libtmux.batch.OperationOutcome
import io.github.libtmux.junit5.NamedServerFixture
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import io.github.libtmux.transport.{
  CommandRequest,
  CommandResult => JavaCommandResult,
  DispatchOutcome,
  OperationReport,
  ProcessTransport,
  TmuxTransport
}
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.{CompletableFuture, ExecutionException, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._
import scala.jdk.OptionConverters._

/** `Batch`/`BatchResult`/`OperationResult`/`CommandChain` remain unwrapped raw
  * Java types on the direct facade, so these tests call their real Java
  * accessors directly through the opaque `Server`/`Pane` handles' generated
  * `batch()`/`chain()` captures, converting Java collections/`Optional` at the
  * boundary. The two Cats-specific cases route through
  * `cats.Batch`/`cats.CommandChain` (new, alongside this port): a fresh Java
  * plan built and dispatched through `Execution` on every `run`, so cancelling
  * one run never touches a concurrent one.
  */
final class BatchChainSuite extends FunSuite {
  private val deadline = Duration.ofMillis(800)

  test(
    "batches preserve order, output rows and unproven Java failure reports"
  ) {
    OwnedTmux.use { fixture =>
      val server = Server.fromJava(fixture.server)
      val pane = server.panes().head
      val batch = pane.batch
        .add("display-message", "-p", "first; still one argument")
        .add(Vector("capture-pane", "-p", "-t", pane.info.id.value()).asJava)
        .add("display-message", "-p", "last")
      assertEquals(batch.size(), 3)
      assert(batch.length() > 0)
      val result = batch.run()
      assert(result.succeeded())
      assertEquals(
        result.operations.asScala.toVector.map(_.outcome()),
        Vector.fill(3)(OperationOutcome.COMPLETE)
      )
      assertEquals(
        result.operations.get(0).stdout.asScala.toVector,
        Vector("first; still one argument")
      )
      assert(!result.operations.get(1).stdout.isEmpty)
      assert(result.operations.get(1).stdout.asScala.forall(_.isEmpty))
      assertEquals(pane.capture(), Vector.empty[String])
      assertEquals(
        result.operations.asScala.last.stdout.asScala.toVector,
        Vector("last")
      )
      assertEquals(result.failure.toScala, None)

      val runtime = server.batch
        .add("display-message", "-p", "before")
        .add("select-window", "-t", "__scala_batch_missing__")
        .add("display-message", "-p", "after")
        .run()
      assertEquals(
        runtime.operations.asScala.toVector.map(_.outcome()),
        Vector(
          OperationOutcome.COMPLETE,
          OperationOutcome.FAILED,
          OperationOutcome.SKIPPED
        )
      )
      assertEquals(
        runtime.operations.get(0).stdout.asScala.toVector,
        Vector("before")
      )
      assertEquals(
        runtime.failure.toScala.map(_.argv.get(0)),
        Some("select-window")
      )

      val rejected = server.batch
        .add("set-option", "-g", "@scala-batch-parse", "must-not-run")
        .add("no-such-scala-batch-command")
        .add("display-message", "-p", "after")
        .run()
      assert(!rejected.succeeded())
      assertEquals(
        rejected.operations.asScala.toVector.map(_.outcome()),
        Vector(
          OperationOutcome.FAILED,
          OperationOutcome.SKIPPED,
          OperationOutcome.SKIPPED
        )
      )
      assertEquals(
        rejected.failure.toScala.map(_.argv.get(0)),
        Some("set-option")
      )
      assert(
        rejected.operations
          .get(0)
          .stderr
          .asScala
          .exists(_.contains("no-such-scala-batch-command"))
      )
      assertEquals(
        server
          .cmd("show-options", "-gqv", "@scala-batch-parse")
          .stdout
          .asScala
          .toVector,
        Vector.empty[String]
      )
      assertEquals(
        server
          .cmd("display-message", "-p", "following")
          .stdout
          .asScala
          .toVector,
        Vector("following")
      )
      // A second, throwaway client: server.fromJava borrows the fixture's own opaque identity, so
      // closing server here would close the fixture's daemon connection out from under its own
      // teardown. This proves the same "closed transport fails a batch built before the close"
      // behavior against a client this test owns instead.
      val throwaway = fixture.own(Server.open(fixture.config))
      val escaped = throwaway.batch.add("display-message", "-p", "closed")
      throwaway.close()
      intercept[IllegalStateException](escaped.run())
    }
  }

  test("chains act on the windows and panes created by preceding steps") {
    OwnedTmux.use { fixture =>
      val server = Server.fromJava(fixture.server)
      val original = server.windows().head.info.context
      val result = server.chain
        .newWindow("created")
        .splitLeftRight()
        .renameWindow("renamed")
        .arrange("even-horizontal")
        .`then`("select-pane", "-T", "chain-target")
        .`then`(Vector("display-message", "-p", "#{pane_id}").asJava)
        .run()
      assert(result.succeeded())
      val created = server.windows().find(_.info.name == "renamed").get
      assert(!created.info.context.equals(original))
      assertEquals(created.panes.size, 2)
      assertEquals(
        result.operations.asScala.last.stdout.asScala.toVector,
        Vector(created.activePane.get.info.id.value())
      )
      assertEquals(
        created.activePane.get.expand("#{pane_title}"),
        "chain-target"
      )
      val prefix =
        fixture.config.endpointCommand().asScala.map(shell).mkString(" ")
      assert(
        server.chain
          .splitTopBottom()
          .sendLine(prefix + " wait-for -S chain-line")
          .run()
          .succeeded()
      )
      assertEquals(
        server.channel("chain-line").await(deadline),
        WakeReason.SIGNALLED
      )
      assertEquals(
        server.windows().find(_.info.name == "renamed").get.panes.size,
        3
      )
      val failed = server.chain
        .newWindow("partial")
        .`then`("select-pane", "-t", "__scala_chain_missing__")
        .newWindow("unreachable")
        .run()
      assertEquals(
        failed.operations.asScala.toVector.map(_.outcome()),
        Vector(
          OperationOutcome.COMPLETE,
          OperationOutcome.FAILED,
          OperationOutcome.SKIPPED
        )
      )
      assert(server.windows().exists(_.info.name == "partial"))
      assert(!server.windows().exists(_.info.name == "unreachable"))
      intercept[IllegalArgumentException](
        server.chain.arrange("not-a-layout")
      )
    }
  }

  test(
    "pane batches retain the captured incarnation guard across replacement"
  ) {
    OwnedTmux.use { fixture =>
      val server = Server.fromJava(fixture.server)
      val pane = server.panes().head
      val batch =
        pane.batch.add("set-option", "-g", "@replacement", "must-not-run")
      fixture.server.killServer()
      fixture.serverProcess.onExit().get(800, TimeUnit.MILLISECONDS)
      fixture.server.newSession(
        SessionSpec.builder().named("replacement").running("cat").build()
      )
      fixture.own(
        NamedServerFixture.own(
          fixture.server,
          fixture.socket,
          fixture.directory
        )
      )
      assert(server.panes().head.info.id.equals(pane.info.id))
      intercept[TargetGoneException](batch.run())
      assertEquals(
        server
          .cmd("show-options", "-gqv", "@replacement")
          .stdout
          .asScala
          .toVector,
        Vector.empty[String]
      )
    }
  }

  test(
    "process batches await deferred work without blocking following requests"
  ) {
    OwnedTmux.use { fixture =>
      val server = Server.fromJava(fixture.server)
      val prefix =
        fixture.config.endpointCommand().asScala.map(shell).mkString(" ")
      val marker = fixture.directory.resolve("batch-completed")
      val script = prefix + " wait-for -S batch-entered; " + prefix +
        " wait-for batch-release; : > " + shell(marker.toString)
      val (worker, result) = start(
        server.batch
          .add("run-shell", script)
          .add("display-message", "-p", "after-deferred")
          .run()
      )
      try {
        assertEquals(
          server.channel("batch-entered").await(deadline),
          WakeReason.SIGNALLED
        )
        assert(!result.isDone)
        assert(!Files.exists(marker))
        assertEquals(
          server
            .cmd("display-message", "-p", "following")
            .stdout
            .asScala
            .toVector,
          Vector("following")
        )
        server.channel("batch-release").signal()
        val completed = result.get(800, TimeUnit.MILLISECONDS)
        assert(completed.succeeded())
        assertEquals(
          completed.operations.asScala.last.stdout.asScala.toVector,
          Vector("after-deferred")
        )
        assert(Files.exists(marker))
      } finally {
        server.channel("batch-release").signal()
        worker.interrupt()
        assert(worker.join(deadline), "batch worker did not exit")
        Files.deleteIfExists(marker)
      }
    }
  }

  test(
    "interrupted batches retain unknown dispatch and already applied effects"
  ) {
    OwnedTmux.use { fixture =>
      val server = Server.fromJava(fixture.server)
      val prefix =
        fixture.config.endpointCommand().asScala.map(shell).mkString(" ")
      val script = prefix + " wait-for -S cancel-entered; " + prefix +
        " wait-for cancel-release; " + prefix + " wait-for -S cancel-finished"
      val (worker, result) = start(
        server.batch
          .add("set-option", "-g", "@scala-partial", "applied")
          .add("run-shell", script)
          .add("set-option", "-g", "@scala-after-cancel", "possible")
          .run()
      )
      try {
        assertEquals(
          server.channel("cancel-entered").await(deadline),
          WakeReason.SIGNALLED
        )
        worker.interrupt()
        val failure = intercept[ExecutionException](
          result.get(800, TimeUnit.MILLISECONDS)
        ).getCause
        assert(failure.isInstanceOf[DispatchException])
        assertEquals(
          failure.asInstanceOf[DispatchException].outcome(),
          DispatchOutcome.UNKNOWN
        )
        assert(failure.getCause.isInstanceOf[InterruptedException])
        assertEquals(
          server
            .cmd("show-options", "-gqv", "@scala-partial")
            .stdout
            .asScala
            .toVector,
          Vector("applied")
        )
        assertEquals(
          server
            .cmd("display-message", "-p", "following-cancel")
            .stdout
            .asScala
            .toVector,
          Vector("following-cancel")
        )
        server.channel("cancel-release").signal()
        assertEquals(
          server.channel("cancel-finished").await(deadline),
          WakeReason.SIGNALLED
        )
      } finally {
        server.channel("cancel-release").signal()
        worker.interrupt()
        assert(worker.join(deadline), "cancelled batch worker did not exit")
      }
    }
  }

  test("Cats plans are immutable and each run allocates a fresh Java builder") {
    OwnedTmux.use { fixture =>
      val calls = new AtomicInteger()
      val transport = fixture.own(new TmuxTransport {
        private val delegate = new ProcessTransport()
        override def execute(request: CommandRequest): JavaCommandResult = {
          calls.incrementAndGet()
          delegate.execute(request)
        }
        override def close(): Unit = delegate.close()
      })
      val java = fixture.own(JavaServer.using(fixture.config, transport))
      cats.Server
        .fromJava[IO](java)
        .use { server =>
          // Scoped to this Cats block: a file-wide wildcard import of the Cats generated
          // extensions would shadow this same file's own direct-style ones of the same name
          // (panes, cmd, windows, ...), which the tests above call throughout.
          import io.github.libtmux.scaladsl.cats.{windows, info}
          val base = server.batch
          val plan = base.add("new-window", "-n", "lazy-batch", "cat")
          val chain =
            server.chain.newWindow("lazy-chain").arrange("even-horizontal")
          val runBatch = plan.run
          val runChain = chain.run
          for {
            _ <- IO {
              assertEquals(calls.get(), 0)
              assertEquals(base.size, 0)
              assertEquals(plan.size, 1)
            }
            first <- runBatch
            second <- runBatch
            chainFirst <- runChain
            chainSecond <- runChain
            windows <- server.windows()
            _ <- IO {
              assertEquals(first.operations.size(), 1)
              assertEquals(second.operations.size(), 1)
              assertEquals(chainFirst.operations.size(), 2)
              assertEquals(chainSecond.operations.size(), 2)
              assertEquals(windows.count(_.info.name == "lazy-batch"), 2)
              assertEquals(windows.count(_.info.name == "lazy-chain"), 2)
            }
          } yield (plan, chain)
        }
        .flatMap { case (batch, chain) =>
          (batch.run.attempt, chain.run.attempt).tupled.flatMap {
            case (batchResult, chainResult) =>
              IO {
                assert(
                  batchResult.left.exists(_.isInstanceOf[IllegalStateException])
                )
                assert(
                  chainResult.left.exists(_.isInstanceOf[IllegalStateException])
                )
              }
          }
        }
        .unsafeRunSync()
    }
  }

  test(
    "Cats batch cancellation is canceled and the observer sees it as unknown"
  ) {
    OwnedTmux.use { fixture =>
      val prefix =
        fixture.config.endpointCommand().asScala.map(shell).mkString(" ")
      val script = prefix + " wait-for -S cats-batch-entered; " + prefix +
        " wait-for cats-batch-release; " + prefix + " wait-for -S cats-batch-finished"
      val reports =
        new java.util.concurrent.ConcurrentLinkedQueue[OperationReport]()
      val observed = fixture.own(
        JavaServer.open(
          fixture.config
            .toBuilder()
            .observer(report => reports.add(report))
            .build()
        )
      )
      cats.Server
        .fromJava[IO](observed)
        .use { server =>
          // Scoped to this Cats block: see the immutable-plans test's own note above.
          import io.github.libtmux.scaladsl.cats.cmd
          val plan = server.batch
            .add("set-option", "-g", "@cats-batch-partial", "applied")
            .add("run-shell", script)
            .add("set-option", "-g", "@cats-batch-after-cancel", "possible")
          val work = for {
            fiber <- plan.run.start
            entered <- IO.interruptible(
              fixture.server.channel("cats-batch-entered").await(deadline)
            )
            _ <- IO(assertEquals(entered, WakeReason.SIGNALLED))
            _ <- fiber.cancel
            outcome <- fiber.join
            before <- server.cmd("show-options", "-gqv", "@cats-batch-partial")
            following <- server.cmd("display-message", "-p", "cats-following")
            _ <- IO {
              assert(outcome.isCanceled)
              assert(
                reports.asScala.exists(report =>
                  report.verbs().contains("run-shell") &&
                    report.certainty().equals(DispatchOutcome.UNKNOWN)
                ),
                reports.asScala.map(_.toString).mkString("\n")
              )
              assertEquals(before.stdout.asScala.toVector, Vector("applied"))
              assertEquals(
                following.stdout.asScala.toVector,
                Vector("cats-following")
              )
            }
            _ <- server.channel("cats-batch-release").signal
            finished <- server
              .channel("cats-batch-finished")
              .await(deadline.toScala)
            _ <- IO(assertEquals(finished, WakeReason.SIGNALLED))
          } yield ()
          work.guarantee(
            IO.interruptible(
              fixture.server.channel("cats-batch-release").signal()
            )
          )
        }
        .unsafeRunSync()
    }
  }

  private def shell(text: String): String =
    "'" + text.replace("'", "'\"'\"'") + "'"

  private def start[A](body: => A): (Thread, CompletableFuture[A]) = {
    val result = new CompletableFuture[A]()
    val worker = Thread
      .ofPlatform()
      .start(() => {
        try result.complete(body)
        catch {
          case failure: Throwable => result.completeExceptionally(failure)
        }
        ()
      })
    (worker, result)
  }
}
