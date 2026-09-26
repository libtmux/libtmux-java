package io.github.libtmux.scaladsl.examples

import _root_.cats.effect.{ExitCode, IOApp}
import _root_.cats.effect.unsafe.implicits.global
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import munit.FunSuite
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Using

final class RunnableExamplesSuite extends FunSuite {
  private val packageName = "io.github.libtmux.scaladsl.examples."
  private val sourceRoot = "src/main/scala/io/github/libtmux/scaladsl/examples/"
  private val mainSources = Map(
    "BlockingWorkspace" -> "BlockingWorkspace.scala",
    "CaptureConcurrently" -> "CaptureConcurrently.scala",
    "ObserveChanges" -> "ObserveChanges.scala",
    "ResourceBoundaries" -> "ResourceBoundaries.scala",
    "WatchWithOx" -> "WatchWithOx.scala"
  )
  private val expectedMains = mainSources.keySet.map(packageName + _)
  // Tests run from this module's directory, as MainsTest's do.
  private val discovered = Using.resource(Files.list(Path.of(sourceRoot))) {
    files =>
      files
        .iterator()
        .asScala
        .map(_.getFileName.toString)
        .filter(name =>
          name.endsWith(".scala") && name != "ExampleRuntime.scala"
        )
        .map(name => packageName + name.stripSuffix(".scala"))
        .toVector
        .sorted
  }

  test("every executable example is registered and indexed") {
    assertEquals(
      discovered.toSet,
      expectedMains,
      "a new example needs a launch here"
    )
    val index = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8)
    val linked = "\\]\\((src/main/scala/[^)]+\\.scala)\\)".r
      .findAllMatchIn(index)
      .map(_.group(1))
      .toSet
    assertEquals(linked, mainSources.values.map(sourceRoot + _).toSet)
  }

  discovered.foreach { name =>
    test("runs actual main: " + name.stripPrefix(packageName)) {
      assert(expectedMains(name), "unregistered executable example")
      OwnedTmux.use { fixture =>
        val sessionsBefore =
          fixture.server.sessions().asScala.map(_.id()).toVector
        val clientsBefore =
          fixture.server.clients().asScala.map(_.name()).toVector
        val marker = "LIBTMUX_SCALA_EXAMPLE_EXECUTED"
        fixture.server.environment().unset(marker)
        assertEquals(fixture.server.environment().get(marker).toScala, None)
        fixture.server
          .hooks()
          .set(
            "session-created",
            Vector("set-environment", "-g", marker, "yes").asJava
          )
        val arguments = Array(
          fixture.config.binary(),
          fixture.socket.toString,
          fixture.config.configFile().orElseThrow().toString
        )
        RunnableExamplesSuite.run(name, arguments, 60000)
        assertEquals(
          fixture.server.environment().get(marker).toScala,
          Some("yes"),
          "main returned without running its tmux effects"
        )
        assert(fixture.server.isAlive(), "example closed the fixture's daemon")
        assertEquals(
          fixture.server.sessions().asScala.map(_.id()).toVector,
          sessionsBefore
        )
        assertEquals(
          fixture.server.clients().asScala.map(_.name()).toVector,
          clientsBefore
        )
      }
    }
  }

  test("a main still running at its deadline fails rather than hangs") {
    val failure = intercept[AssertionError](
      RunnableExamplesSuite.run(
        packageName + "HangingMain",
        Array.empty[String],
        200
      )
    )
    assert(failure.getMessage.contains("did not return"), failure.getMessage)
  }
}

object RunnableExamplesSuite {

  /** Runs the example `name`: an `IOApp`'s `run`, which is its whole program,
    * or else its `main`. `IOApp.main` would end this JVM on a failure; its
    * effect run here throws instead.
    */
  def run(name: String, arguments: Array[String], deadlineMillis: Long): Unit =
    Class.forName(name + "$").getField("MODULE$").get(null) match {
      case app: IOApp =>
        run(
          name,
          () => {
            val code = app.run(arguments.toList).unsafeRunSync()
            if (!code.equals(ExitCode.Success))
              throw new AssertionError(s"$name exited with $code")
          },
          deadlineMillis
        )
      case _ =>
        val main = Class.forName(name).getMethod("main", classOf[Array[String]])
        run(
          name,
          () => { main.invoke(null, arguments.asInstanceOf[Object]); () },
          deadlineMillis
        )
    }

  /** Runs `body` on a thread of its own, failing once `deadlineMillis` passes.
    * A daemon, so a body that never returns cannot hold the JVM either.
    */
  def run(name: String, body: () => Unit, deadlineMillis: Long): Unit = {
    val failure = new AtomicReference[Throwable]()
    val finished = new CountDownLatch(1)
    val runner: Runnable = () =>
      try body()
      catch {
        case thrown: InvocationTargetException => failure.set(thrown.getCause)
        case thrown: Throwable                 => failure.set(thrown)
      } finally finished.countDown()
    val thread = new Thread(runner, "example-" + name)
    thread.setDaemon(true)
    thread.start()
    if (!finished.await(deadlineMillis, TimeUnit.MILLISECONDS)) {
      thread.interrupt()
      throw new AssertionError(
        s"$name did not return within $deadlineMillis ms"
      )
    }
    Option(failure.get).foreach(thrown => throw thrown)
  }
}

/** Never returns. Top level, so its main is static, as an example's is. */
object HangingMain {
  def main(args: Array[String]): Unit = Thread.sleep(Long.MaxValue)
}
