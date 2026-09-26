package io.github.libtmux.scaladsl.benchmarks

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all._
import fs2.Stream
import io.github.libtmux.{
  Server => JavaServer,
  ServerConfig,
  ServerEndpoint,
  SessionId,
  SessionSpec,
  SplitSpec
}
import io.github.libtmux.scaladsl.{Pane, Server}
import io.github.libtmux.scaladsl.{config => _, *}
import io.github.libtmux.scaladsl.cats.{Control, Observation}
import io.github.libtmux.scaladsl.cats.{config => _, *}
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Using

/** Measures equivalent, verified tmux workloads. The JSON is descriptive raw
  * evidence, not a capacity or latency claim.
  */
object ModeBenchmarks {
  private val deadline = Duration.ofMillis(800)

  private final case class Input(
      binary: String,
      socket: Path,
      config: Path,
      output: Path,
      warmups: Int,
      samples: Int
  )

  private final case class Counts(
      children: Int,
      clients: Int,
      workers: Int
  )

  private final case class Sample(
      elapsedNanos: Long,
      allocatedBytes: Option[Long],
      droppedEvents: Long,
      before: Counts,
      after: Counts
  )

  private final case class Result(
      name: String,
      samples: Vector[Sample]
  )

  def main(arguments: Array[String]): Unit = {
    val input = parse(arguments)
    require(
      !Files.exists(input.socket),
      "benchmark socket must not already exist"
    )
    Files.createDirectories(input.socket.getParent)
    val config = ServerConfig
      .builder()
      .binary(input.binary)
      .endpoint(ServerEndpoint.socketPath(input.socket))
      .configFile(input.config)
      .defaultTimeout(deadline)
      .build()
    val java = JavaServer.open(config)
    var daemon: Option[ProcessHandle] = None
    try {
      val blocking = Server.fromJava(java)
      val session = blocking.newSession(
        SessionSpec
          .builder()
          .named("scala-benchmark-" + UUID.randomUUID().toString.take(8))
          .running("/bin/sh")
          .build()
      )
      daemon = Some(
        ProcessHandle
          .of(
            java.cmd("display-message", "-p", "#{pid}").stdout().get(0).toLong
          )
          .orElseThrow()
      )
      try run(input, java, blocking, session.info.id())
      finally session.kill()
    } finally {
      try java.killServer()
      catch { case _: RuntimeException => () }
      daemon.foreach { process =>
        if (process.isAlive)
          process.onExit().get(deadline.toMillis, TimeUnit.MILLISECONDS)
        require(!process.isAlive, "benchmark daemon survived cleanup")
      }
      java.close()
      Files.deleteIfExists(input.socket)
      require(
        !Files.exists(input.socket),
        "benchmark daemon socket survived cleanup"
      )
    }
  }

  private def run(
      input: Input,
      java: JavaServer,
      blocking: Server,
      session: SessionId
  ): Unit = {
    val first = blocking.panes().head
    first.split(SplitSpec.builder().running("/bin/sh").build())
    first.split(SplitSpec.builder().running("/bin/sh").build())
    val expected = blocking.panes().map(_.info.id().value())
    require(expected.size == 3, "benchmark topology must contain three panes")
    val markers = expected.map(id => "scala-benchmark-ready-" + id)
    blocking.panes().zip(markers).foreach { case (pane, marker) =>
      require(
        pane
          .run(
            "printf '%s\\n' " + marker,
            scala.jdk.DurationConverters.JavaDurationOps(deadline).toScala
          )
          .succeeded,
        "benchmark setup command failed"
      )
    }
    val ready = capture(blocking.panes())
    require(
      ready.ids == expected && ready.lines.zip(markers).forall {
        case (lines, marker) => lines.exists(_.contains(marker))
      },
      "benchmark setup did not create the expected captured topology"
    )

    val listCommand =
      Vector("list-panes", "-t", session.value(), "-F", "#{pane_id}")
    val results = Vector.newBuilder[Result]
    results += measure("java_process_capture", input, blocking) { () =>
      val panes = java.panes().asScala.toVector
      Captured(
        panes.map(_.id().value()),
        panes.map(_.capture().asScala.toVector)
      )
    }(ready)
    results += measure("scala_blocking_capture", input, blocking) { () =>
      capture(blocking.panes())
    }(ready)

    val cats = io.github.libtmux.scaladsl.cats.Server
      .fromJava[IO](java, maxConcurrentCalls = 4)
      .allocated
      .unsafeRunSync()
    val catsServer = cats._1
    val releaseCats = cats._2
    try {
      results += measure("cats_serial_capture", input, blocking) { () =>
        catsServer
          .panes()
          .flatMap { panes =>
            panes.toList.traverse(pane =>
              pane.capture().map(lines => pane.info.id().value() -> lines)
            )
          }
          .map(values =>
            Captured(values.map(_._1).toVector, values.map(_._2).toVector)
          )
          .unsafeRunSync()
      }(ready)
      results += measure("cats_bounded_capture", input, blocking) { () =>
        catsServer
          .panes()
          .flatMap { panes =>
            Stream
              .emits(panes)
              .covary[IO]
              .parEvalMap(2)(pane =>
                pane.capture().map(lines => pane.info.id().value() -> lines)
              )
              .compile
              .toVector
          }
          .map(values => Captured(values.map(_._1), values.map(_._2)))
          .unsafeRunSync()
      }(ready)
      // cats_batch/cats_chain measurements are pending: Batch/CommandChain are not yet part of the
      // Scala 3 rewrite's generated or handwritten surface (see the report's "deferred" list).

      val control = Control
        .attachUnfenced[IO](inputConfig(input), session, deadline)
        .allocated
        .unsafeRunSync()
      val attachment = control._1
      val releaseControl = control._2
      try {
        results += measure("admitted_control_list", input, blocking) { () =>
          val reply =
            attachment.acknowledge(listCommand, deadline).unsafeRunSync()
          require(reply.accepted, "control command was rejected")
          Captured(reply.lines, Vector.empty)
        }(Captured(expected, Vector.empty))
        results += measure("control_idle_subscription", input, blocking) { () =>
          Captured(
            expected,
            Vector.empty,
            attachment.output(1).use(_.droppedCount).unsafeRunSync()
          )
        }(Captured(expected, Vector.empty))
        results += measure("polling_marker", input, blocking) { () =>
          val marker = markerFor("poll")
          first.sendLine("printf '%s\\n' " + marker)
          poll(first, marker)
          Captured(expected, Vector.empty)
        }(Captured(expected, Vector.empty))
        results += measure("pushed_marker", input, blocking) { () =>
          val marker = markerFor("push")
          val dropped = attachment
            .output(32)
            .use { observation =>
              val observed = observation.stream
                .map(Observation.value)
                .unNone
                .filter(_.pane().value() == first.info.id().value())
                .map(_.data())
                .scan("")((text, chunk) => (text + chunk).takeRight(512))
                .filter(_.contains(marker))
                .take(1)
                .compile
                .lastOrError
                .timeout(deadline.toMillis.millis)
              (
                IO.interruptible(
                  first.asJava.sendLine("printf '%s\\n' " + marker)
                ),
                observed
              ).parTupled.void *> observation.droppedCount
            }
            .unsafeRunSync()
          Captured(expected, Vector.empty, dropped)
        }(Captured(expected, Vector.empty))
        results += measure("subscription_cancel_and_close", input, blocking) {
          () =>
            attachment
              .output(1)
              .use { observation =>
                observation.stream.compile.drain.start.flatMap { reader =>
                  reader.cancel *> observation.isClosed.map(_ => ())
                }
              }
              .unsafeRunSync()
            Captured(expected, Vector.empty)
        }(Captured(expected, Vector.empty))
      } finally releaseControl.unsafeRunSync()
    } finally releaseCats.unsafeRunSync()
    Files.writeString(input.output, render(input, blocking, results.result()))
  }

  private final case class Captured(
      ids: Vector[String],
      lines: Vector[Vector[String]],
      droppedEvents: Long = 0L
  ) derives CanEqual

  private def capture(panes: Vector[Pane]): Captured =
    Captured(panes.map(_.info.id().value()), panes.map(_.capture()))

  private def poll(pane: Pane, marker: String): Unit = {
    val stop = System.nanoTime() + deadline.toNanos
    while (System.nanoTime() < stop) {
      if (pane.capture().exists(_.contains(marker))) return
    }
    throw new java.util.concurrent.TimeoutException("polling marker: " + marker)
  }

  private def measure(
      name: String,
      input: Input,
      server: Server
  )(operation: () => Captured)(expected: Captured): Result = {
    Vector.fill(input.warmups) {
      require(operation() == expected, "warmup value differs: " + name)
    }
    val samples = Vector.fill(input.samples) {
      val before = counts(server)
      val allocatedBefore = allocatedBytes()
      val started = System.nanoTime()
      val actual = operation()
      val elapsed = System.nanoTime() - started
      val allocatedAfter = allocatedBytes()
      require(actual == expected, "sample value differs: " + name)
      Sample(
        elapsed,
        for {
          before <- allocatedBefore
          after <- allocatedAfter
        } yield after - before,
        actual.droppedEvents,
        before,
        counts(server)
      )
    }
    Result(name, samples)
  }

  private def counts(server: Server): Counts = Counts(
    ProcessHandle.current().children().count().toInt,
    server.clients().size,
    Thread.getAllStackTraces
      .keySet()
      .asScala
      .count(thread => thread.isAlive && thread.getName.startsWith("libtmux-"))
  )

  private def allocatedBytes(): Option[Long] =
    ManagementFactory.getThreadMXBean match {
      case bean: com.sun.management.ThreadMXBean
          if bean.isThreadAllocatedMemorySupported =>
        if (!bean.isThreadAllocatedMemoryEnabled)
          bean.setThreadAllocatedMemoryEnabled(true)
        Some(bean.getThreadAllocatedBytes(Thread.currentThread().threadId()))
      case _ => None
    }

  private def inputConfig(input: Input): ServerConfig =
    ServerConfig
      .builder()
      .binary(input.binary)
      .endpoint(ServerEndpoint.socketPath(input.socket))
      .configFile(input.config)
      .defaultTimeout(deadline)
      .build()

  private def markerFor(kind: String): String =
    "scala-benchmark-" + kind + "-" + UUID
      .randomUUID()
      .toString
      .replace("-", "")

  private def parse(arguments: Array[String]): Input = {
    require(
      arguments.length == 4 || arguments.length == 6,
      "expected: tmux-binary socket-path config-file output-json [warmups samples]"
    )
    val warmups = if (arguments.length == 6) arguments(4).toInt else 3
    val samples = if (arguments.length == 6) arguments(5).toInt else 9
    require(
      warmups >= 0 && samples > 0,
      "warmups must be nonnegative and samples positive"
    )
    Input(
      arguments(0),
      Path.of(arguments(1)),
      Path.of(arguments(2)),
      Path.of(arguments(3)),
      warmups,
      samples
    )
  }

  private def render(
      input: Input,
      server: Server,
      results: Vector[Result]
  ): String = {
    val samples = results.map(result =>
      "{" +
        "\"name\":" + quote(result.name) + "," +
        "\"summary\":" + summary(result.samples) + "," +
        "\"samples\":" + result.samples
          .map(renderSample)
          .mkString("[", ",", "]") +
        "}"
    )
    "{" +
      "\"source\":{" +
      "\"git_head\":" + quote(gitHead()) + "," +
      "\"scala\":" + quote(scala.util.Properties.versionNumberString) + "," +
      "\"scala_source_sha256\":" + quote(scalaSourceSha256()) + "," +
      "\"java_artifact_sha256\":" + quote(
        codeSourceSha256(classOf[JavaServer])
      ) + "," +
      "\"java\":" + quote(System.getProperty("java.version")) + "," +
      "\"tmux\":" + quote(server.version().toString) +
      "}," +
      "\"command\":{" +
      "\"binary\":" + quote(input.binary) + "," +
      "\"socket\":" + quote(input.socket.toString) + "," +
      "\"warmups\":" + input.warmups + "," +
      "\"samples\":" + input.samples +
      "}," +
      "\"results\":" + samples.mkString("[", ",", "]") +
      "}\n"
  }

  private def renderSample(value: Sample): String =
    "{" +
      "\"elapsed_nanos\":" + value.elapsedNanos + "," +
      "\"allocated_bytes\":" + value.allocatedBytes.getOrElse("null") + "," +
      "\"dropped_events\":" + value.droppedEvents + "," +
      "\"before\":" + renderCounts(value.before) + "," +
      "\"after\":" + renderCounts(value.after) +
      "}"

  private def renderCounts(value: Counts): String =
    s"{\"children\":${value.children},\"clients\":${value.clients},\"workers\":${value.workers}}"

  private def summary(samples: Vector[Sample]): String = {
    val elapsed = samples.map(_.elapsedNanos).sorted
    val total = elapsed.map(_.toDouble).sum
    "{" +
      "\"p50_nanos\":" + percentile(elapsed, 0.50) + "," +
      "\"p95_nanos\":" + percentile(elapsed, 0.95) + "," +
      "\"p99_nanos\":" + percentile(elapsed, 0.99) + "," +
      "\"throughput_per_second\":" + (samples.size * 1000000000.0 / total) +
      "}"
  }

  private def percentile(values: Vector[Long], percentile: Double): Long =
    values(math.max(0, math.ceil(values.size * percentile).toInt - 1))

  private def gitHead(): String =
    try {
      val process = new ProcessBuilder("git", "rev-parse", "HEAD").start()
      val result =
        new String(process.getInputStream.readAllBytes(), "UTF-8").trim
      if (process.waitFor() == 0) result else "unavailable"
    } catch { case _: Exception => "unavailable" }

  private def scalaSourceSha256(): String = {
    val repository = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(path =>
        Files.isRegularFile(path.resolve("build.sbt")) &&
          Files.isDirectory(path.resolve("libtmux-scala")) &&
          Files.isDirectory(path.resolve("libtmux-scala-cats"))
      )
      .getOrElse(
        throw new IllegalStateException("Scala repository root is missing")
      )
    val inputs = Vector(
      repository.resolve("build.sbt"),
      repository.resolve("project"),
      repository.resolve(".scalafmt.conf"),
      repository.resolve("libtmux-scala"),
      repository.resolve("libtmux-scala-cats")
    )
    val entries = inputs.flatMap { input =>
      val files =
        if (Files.isRegularFile(input)) Vector(input)
        else
          Using.resource(Files.walk(input)) { paths =>
            paths
              .iterator()
              .asScala
              .filter(path =>
                Files.isRegularFile(path) && !path
                  .iterator()
                  .asScala
                  .exists(_.toString == "target")
              )
              .toVector
          }
      files.map { path =>
        repository.relativize(path).toString.replace('\\', '/') + "\t" +
          sha256(Files.readAllBytes(path))
      }
    }
    sha256(entries.sorted.mkString("\n").getBytes("UTF-8"))
  }

  private def sha256(bytes: Array[Byte]): String =
    java.util.HexFormat
      .of()
      .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def codeSourceSha256(value: Class[?]): String =
    try {
      val source = value.getProtectionDomain.getCodeSource.getLocation
      sha256(Files.readAllBytes(Path.of(source.toURI)))
    } catch { case _: Exception => "unavailable" }

  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
