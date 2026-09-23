package io.github.libtmux.scaladsl

import _root_.cats.effect.{Deferred, IO}
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all._
import io.github.libtmux.{SessionId, WakeReason}
import io.github.libtmux.control.{EventSubscription, Notification, PaneOutput}
import io.github.libtmux.scaladsl.cats.{Control, Observation}
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import io.github.libtmux.transport.{DispatchOutcome, TmuxTransportException}
import java.io.BufferedWriter
import java.nio.file.Files
import java.time.Duration
import java.util.Date
import java.util.concurrent.{ArrayBlockingQueue, CompletableFuture, TimeUnit}
import java.util.concurrent.locks.{Condition, ReentrantLock}
import munit.FunSuite
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

final class ControlObservationSuite extends FunSuite {
  private val deadline = Duration.ofMillis(800)

  private def attach(fixture: OwnedTmux) = Control.attach[IO](
    fixture.config,
    fixture.server.sessions().get(0).id(),
    deadline
  )

  // The observer acquires the lock only after Condition.await releases it.
  private def parked[A](subscription: EventSubscription[A]): IO[Thread] = {
    val result = new CompletableFuture[Thread]()
    val lockField = classOf[EventSubscription[_]].getDeclaredField("lock")
    val availableField =
      classOf[EventSubscription[_]].getDeclaredField("available")
    lockField.setAccessible(true)
    availableField.setAccessible(true)
    val lock = lockField.get(subscription).asInstanceOf[ReentrantLock]
    val original = availableField.get(subscription).asInstanceOf[Condition]
    val observed = new Condition {
      override def await(): Unit = {
        val reader = Thread.currentThread()
        val observer = new Thread(
          () => {
            lock.lock()
            try {
              if (lock.hasWaiters(original)) result.complete(reader)
              else
                result.completeExceptionally(
                  new AssertionError("reader did not await")
                )
            } finally lock.unlock()
            ()
          },
          "scala-observation-park-probe"
        )
        observer.setDaemon(true)
        observer.start()
        original.await()
      }
      override def awaitUninterruptibly(): Unit =
        original.awaitUninterruptibly()
      override def awaitNanos(nanos: Long): Long = original.awaitNanos(nanos)
      override def await(time: Long, unit: TimeUnit): Boolean =
        original.await(time, unit)
      override def awaitUntil(date: Date): Boolean = original.awaitUntil(date)
      override def signal(): Unit = original.signal()
      override def signalAll(): Unit = original.signalAll()
    }
    availableField.set(subscription, observed)
    IO.fromCompletableFuture(IO.pure(result))
  }

  private def writer(control: Control[IO]): AnyRef = {
    val field = control.underlying.getClass.getDeclaredField("writer")
    field.setAccessible(true)
    field.get(control.underlying)
  }

  private def written(control: Control[IO]): IO[Unit] = {
    val completed = new CompletableFuture[Unit]()
    val target = writer(control)
    val field = target.getClass.getDeclaredField("output")
    field.setAccessible(true)
    val original = field.get(target).asInstanceOf[BufferedWriter]
    field.set(
      target,
      new BufferedWriter(original) {
        override def flush(): Unit = {
          super.flush()
          completed.complete(())
          ()
        }
      }
    )
    IO.fromCompletableFuture(IO.pure(completed))
  }

  private def enqueued(control: Control[IO]): IO[Unit] = {
    val completed = new CompletableFuture[Unit]()
    val target = writer(control)
    val field = target.getClass.getDeclaredField("waiting")
    field.setAccessible(true)
    val original = field.get(target).asInstanceOf[ArrayBlockingQueue[AnyRef]]
    assert(original.isEmpty())
    field.set(
      target,
      new ArrayBlockingQueue[AnyRef](original.remainingCapacity(), true) {
        override def offer(
            value: AnyRef,
            timeout: Long,
            unit: TimeUnit
        ): Boolean = {
          val accepted = super.offer(value, timeout, unit)
          if (accepted) completed.complete(())
          accepted
        }
      }
    )
    IO.fromCompletableFuture(IO.pure(completed))
  }

  test("registered output retains pane identity across forced marker chunks") {
    OwnedTmux.use { fixture =>
      val pane = fixture.server.panes().get(0)
      val workersBefore = Thread.getAllStackTraces
        .keySet()
        .asScala
        .filter(_.getName.startsWith("libtmux-control"))
        .map(_.threadId())
        .toSet
      var subscription: EventSubscription[PaneOutput] = null
      attach(fixture)
        .use { control =>
          control
            .output(16)
            .use { observation =>
              subscription = observation.underlying
              for {
                _ <- IO {
                  val workers = Thread.getAllStackTraces
                    .keySet()
                    .asScala
                    .filter(thread =>
                      thread.isAlive && thread.getName.startsWith(
                        "libtmux-control"
                      ) && !workersBefore(thread.threadId())
                    )
                  assertEquals(workers.size, 3)
                }
                first <- Deferred[IO, Unit]
                reader <- observation.stream
                  .map(Observation.value)
                  .unNone
                  .evalTap(output => IO(assertEquals(output.pane(), pane.id())))
                  .scan(Vector.empty[String])((parts, output) =>
                    parts :+ output.data()
                  )
                  .evalTap(parts =>
                    if (parts.mkString.contains("chunk-"))
                      first.complete(()).void
                    else IO.unit
                  )
                  .filter(_.mkString.contains("chunk-boundary"))
                  .take(1)
                  .compile
                  .lastOrError
                  .start
                _ <- IO.interruptible(
                  pane.sendLiteral(java.util.List.of("chunk-"))
                )
                _ <- first.get.timeout(800.millis)
                _ <- IO.interruptible(pane.sendLine("boundary"))
                parts <- reader.joinWithNever.timeout(800.millis)
                loss <- observation.droppedCount
                _ <- IO {
                  assert(parts.size >= 2)
                  assert(!parts.head.contains("chunk-boundary"))
                  assertEquals(loss, 0L)
                }
              } yield ()
            }
            .flatMap(_ => IO(assert(subscription.isClosed())))
        }
        .unsafeRunSync()
      assert(subscription.isClosed())
      assert(fixture.server.isAlive())
    }
  }

  test("a parked reader cancels and releases the single consumer slot") {
    OwnedTmux.use { fixture =>
      attach(fixture)
        .use { control =>
          control.output(4).use { observation =>
            val awaited = parked(observation.underlying)
            for {
              reader <- observation.stream.compile.drain.start
              worker <- awaited
              competing <- observation.stream.take(1).compile.drain.attempt
              _ <- IO {
                assert(worker.getName.startsWith("io-compute-blocker-"))
                val parkedReaders =
                  Thread.getAllStackTraces.asScala.values.count(
                    _.exists(frame =>
                      frame.getClassName == classOf[
                        EventSubscription[_]
                      ].getName && frame.getMethodName == "next"
                    )
                  )
                assertEquals(parkedReaders, 1)
                assert(
                  competing.left.exists(_.isInstanceOf[IllegalStateException])
                )
              }
              _ <- reader.cancel
              outcome <- reader.join
              _ <- IO(assert(outcome.isCanceled))
              next <- observation.stream.take(1).compile.lastOrError.start
              _ <- IO.interruptible(
                fixture.server.panes().get(0).sendLine("after-cancel")
              )
              output <- next.joinWithNever
              _ <- IO(assert(Observation.value(output).exists(_.data().nonEmpty)))
            } yield ()
          }
        }
        .timeout(1.second)
        .unsafeRunSync()
    }
  }

  test("attachment is lazy and failed acquisition reclaims its client") {
    OwnedTmux.use { fixture =>
      val before = fixture.server.clients().size()
      val acquisition = Control
        .attach[IO](fixture.config, new SessionId("$2147483647"), deadline)
      assertEquals(fixture.server.clients().size(), before)
      val failed =
        acquisition.use(_ => IO.unit).attempt.timeout(1.second).unsafeRunSync()
      assert(failed.isLeft)
      assertEquals(fixture.server.clients().size(), before)
      val invalid = Control
        .attach[IO](
          fixture.config,
          new SessionId("$2147483647"),
          deadline,
          maxConcurrentCalls = 0
        )
        .use(_ => IO.unit)
        .attempt
        .timeout(1.second)
        .unsafeRunSync()
      assert(invalid.left.exists(_.isInstanceOf[IllegalArgumentException]))
      assertEquals(fixture.server.clients().size(), before)
    }
  }

  test("typed notifications report overflow and reconcile current state") {
    OwnedTmux.use { fixture =>
      val window = fixture.server.windows().get(0)
      window.options().set("automatic-rename", "off")
      attach(fixture)
        .use { control =>
          val slowCapacity = 2
          (control.events(slowCapacity), control.events(16)).tupled.use {
            case (slow, witness) =>
              val names = Vector.tabulate(5)(index => "overflow-" + index)
              val minimumExpectedLoss = (names.size - slowCapacity).toLong
              for {
                _ <- names.traverse_ { name =>
                  for {
                    received <- witness.stream
                      .map(Observation.value)
                      .unNone
                      .filter(_.notification() match {
                        case event: Notification.WindowRenamed =>
                          event.name() == name
                        case _ => false
                      })
                      .take(1)
                      .compile
                      .lastOrError
                      .start
                    reply <- control.acknowledge(
                      Vector("rename-window", "-t", window.id().value(), name),
                      deadline
                    )
                    _ <- IO(assert(reply.accepted))
                    _ <- received.joinWithNever
                  } yield ()
                }
                loss <- slow.droppedCount
                snapshot <- IO.interruptible(fixture.server.snapshot())
                _ <- IO {
                  assert(
                    loss >= minimumExpectedLoss,
                    "loss=" + loss + ", expected at least " +
                      minimumExpectedLoss
                  )
                  assertEquals(snapshot.windows().get(0).name(), names.last)
                }
              } yield ()
          }
        }
        .timeout(1.second)
        .unsafeRunSync()
    }
  }

  test(
    "scope close ends a parked stream and server loss fails it with the client's cause"
  ) {
    OwnedTmux.use { fixture =>
      val result = attach(fixture).allocated.flatMap { case (control, close) =>
        control.output(4).allocated.flatMap { case (observation, unsubscribe) =>
          val awaited = parked(observation.underlying)
          (for {
            reader <- observation.stream.compile.drain.start
            _ <- awaited
            _ <- control.acknowledge(Vector("wait-for", "owner-gate"), deadline)
            dispatched <- IO(written(control))
            escaped <- control
              .acknowledge(
                Vector("display-message", "-p", "escaped"),
                deadline
              )
              .start
            _ <- dispatched
            _ <- close
            outcome <- reader.join
            escapedOutcome <- escaped.join
            _ <- IO {
              assert(outcome.isSuccess)
              assert(escapedOutcome.isCanceled)
              assert(observation.underlying.isClosed())
            }
          } yield ()).guarantee(unsubscribe *> close)
        }
      }
      result.timeout(1.second).unsafeRunSync()
      assert(fixture.server.isAlive())
      attach(fixture)
        .use { control =>
          control.output(4).use { observation =>
            val awaited = parked(observation.underlying)
            for {
              reader <- observation.stream.compile.drain.attempt.start
              _ <- awaited
              _ <- IO.interruptible(fixture.server.killServer())
              outcome <- reader.joinWithNever
              _ <- IO {
                assert(
                  outcome.left.exists(
                    _.isInstanceOf[
                      io.github.libtmux.control.ControlEndedException
                    ]
                  ),
                  outcome.toString
                )
                assert(observation.underlying.isClosed())
              }
            } yield ()
          }
        }
        .timeout(1.second)
        .unsafeRunSync()
    }
  }

  test(
    "acknowledgement retains raw reply without implying deferred completion"
  ) {
    OwnedTmux.use { fixture =>
      val marker = fixture.directory.resolve("ack-completed")
      val prefix = fixture.config
        .endpointCommand()
        .asScala
        .map(word => "'" + word.replace("'", "'\"'\"'") + "'")
        .mkString(" ")
      val script = prefix + " wait-for scala-ack-release; : > '" + marker +
        "'; " + prefix + " wait-for -S scala-ack-completed"
      val program = attach(fixture)
        .use { control =>
          for {
            rejected <- control.acknowledge(
              Vector("no-such-scala-command"),
              deadline
            )
            accepted <- control.acknowledge(
              Vector("run-shell", script),
              deadline
            )
            _ <- IO {
              assert(!rejected.accepted)
              assert(!rejected.asJava.lines().isEmpty())
              assert(accepted.accepted)
              assert(!Files.exists(marker))
            }
            _ <- IO.interruptible(
              fixture.server.channel("scala-ack-release").signal()
            )
            done <- IO.interruptible(
              fixture.server.channel("scala-ack-completed").await(deadline)
            )
            _ <- IO {
              assertEquals(done, WakeReason.SIGNALLED)
              assert(Files.exists(marker))
            }
          } yield ()
        }
        .guarantee(
          IO.interruptible(fixture.server.channel("scala-ack-release").signal())
        )
      program.timeout(1.second).unsafeRunSync()
    }
  }

  test(
    "queued cancellation preserves control but active cancellation ends its peers"
  ) {
    OwnedTmux.use { fixture =>
      attach(fixture)
        .use { control =>
          for {
            gate <- control.acknowledge(
              Vector("wait-for", "queued-gate"),
              deadline
            )
            _ <- IO(assert(gate.accepted))
            dispatched <- IO(written(control))
            active <- control
              .acknowledge(Vector("display-message", "-p", "active"), deadline)
              .start
            _ <- dispatched
            admitted <- IO(enqueued(control))
            queued <- control
              .acknowledge(Vector("display-message", "-p", "queued"), deadline)
              .start
            _ <- admitted
            _ <- queued.cancel
            canceled <- queued.join
            _ <- IO(assert(canceled.isCanceled))
            _ <- IO.interruptible(
              fixture.server.channel("queued-gate").signal()
            )
            reply <- active.joinWithNever
            _ <- IO(assertEquals(reply.lines, Vector("active")))
            following <- control.acknowledge(
              Vector("display-message", "-p", "following"),
              deadline
            )
            _ <- IO(assertEquals(following.lines, Vector("following")))
            _ <- control.acknowledge(
              Vector("wait-for", "active-gate"),
              deadline
            )
            dispatchedAgain <- IO(written(control))
            canceledActive <- control
              .acknowledge(
                Vector("display-message", "-p", "unanswered"),
                deadline
              )
              .start
            _ <- dispatchedAgain
            admittedAgain <- IO(enqueued(control))
            peer <- control
              .acknowledge(Vector("display-message", "-p", "peer"), deadline)
              .attempt
              .start
            _ <- admittedAgain
            _ <- canceledActive.cancel
            activeOutcome <- canceledActive.join
            peerOutcome <- peer.joinWithNever
            unavailable <- control
              .acknowledge(Vector("display-message", "-p", "after"), deadline)
              .attempt
            processReply <- IO.interruptible(
              fixture.server.cmd("display-message", "-p", "separate-client")
            )
            _ <- IO {
              assert(activeOutcome.isCanceled)
              assert(peerOutcome.left.exists {
                case failure: TmuxTransportException =>
                  failure.outcome() == DispatchOutcome.NOT_DISPATCHED
                case _ => false
              })
              assert(
                unavailable.left.exists(_.isInstanceOf[IllegalStateException])
              )
              assertEquals(
                processReply.stdout().asScala.toVector,
                Vector("separate-client")
              )
            }
          } yield ()
        }
        .timeout(1.second)
        .unsafeRunSync()
    }
  }
}
