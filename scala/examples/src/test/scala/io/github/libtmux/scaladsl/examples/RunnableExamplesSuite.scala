package io.github.libtmux.scaladsl.examples

import io.github.libtmux.scaladsl.fixture.OwnedTmux
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.HexFormat
import munit.FunSuite
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Using

final class RunnableExamplesSuite extends FunSuite {
  private val packageName = "io.github.libtmux.scaladsl.examples."
  private val mainSources = Map(
    "BlockingWorkspace" -> "BlockingWorkspace.scala",
    "CaptureConcurrently" -> "CaptureConcurrently.scala",
    "ObserveChanges" -> "ObserveChanges.scala",
    "ResourceBoundaries" -> "ResourceBoundaries.scala"
  )
  private val expectedMains = mainSources.keySet.map(packageName + _)
  private val discovered = resourceLines("example-mains.txt")
  private val sourceRoot =
    "scala/examples/src/main/scala/io/github/libtmux/scaladsl/examples/"
  private val root = Iterator
    .iterate(Path.of("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(path => Files.isRegularFile(path.resolve("scala/build.sbt")))
    .getOrElse(
      throw new IllegalStateException("Scala repository root is missing")
    )

  test(
    "every executable example is discovered, indexed and compiled from current source"
  ) {
    assertEquals(discovered.toSet, expectedMains)
    assertEquals(discovered.distinct.size, discovered.size)
    val compiled = resourceLines("example-sources.txt").map { line =>
      val fields = line.split("\t", -1)
      assertEquals(fields.length, 2)
      fields(0) -> fields(1)
    }
    val actual = Using.resource(
      Files.walk(root.resolve("scala/examples/src/main/scala"))
    ) { files =>
      files
        .iterator()
        .asScala
        .filter(path =>
          Files.isRegularFile(path) && path.toString.endsWith(".scala")
        )
        .map(path => root.relativize(path).toString.replace('\\', '/'))
        .toSet
    }
    assertEquals(compiled.map(_._1).toSet, actual)
    assertEquals(compiled.map(_._1).distinct.size, compiled.size)
    val expectedSources =
      (mainSources.values.toSet + "ExampleRuntime.scala").map(sourceRoot + _)
    assertEquals(actual, expectedSources)
    compiled.foreach { case (path, expectedHash) =>
      val digest = MessageDigest
        .getInstance("SHA-256")
        .digest(Files.readAllBytes(root.resolve(path)))
      assertEquals(HexFormat.of().formatHex(digest), expectedHash, path)
    }
    val index = Files.readString(
      root.resolve("scala/examples/README.md"),
      StandardCharsets.UTF_8
    )
    val linked = "\\[[^\\]]+\\]\\[([^\\]]+)\\]".r
      .findAllMatchIn(index)
      .map(_.group(1))
      .toSet
    val indexed =
      "(?m)^\\[([^\\]]+)\\]:[ \\t]*(?:\\r?\\n[ \\t]+)?(src/main/scala/\\S+\\.scala)[ \\t]*$".r
        .findAllMatchIn(index)
        .map(matched =>
          matched.group(1) -> ("scala/examples/" + matched.group(2))
        )
        .toMap
    assertEquals(linked, indexed.keySet)
    assertEquals(
      indexed.values.toSet,
      mainSources.values.map(sourceRoot + _).toSet
    )
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
        val main = Class.forName(name).getMethod("main", classOf[Array[String]])
        try main.invoke(null, arguments.asInstanceOf[Object])
        catch {
          case failure: InvocationTargetException => throw failure.getCause
        }
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

  private def resourceLines(name: String): Vector[String] = {
    val stream = Option(getClass.getClassLoader.getResourceAsStream(name))
      .getOrElse(
        throw new IllegalStateException("missing example inventory: " + name)
      )
    Using.resource(Source.fromInputStream(stream, "UTF-8"))(
      _.getLines().filter(_.nonEmpty).toVector
    )
  }
}
