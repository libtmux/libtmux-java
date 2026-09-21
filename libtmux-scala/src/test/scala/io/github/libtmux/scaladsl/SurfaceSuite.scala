package io.github.libtmux.scaladsl

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters._

/** Every public method on the five Java handles is wrapped or named here. */
final class SurfaceSuite extends FunSuite {
  private val types = Vector("Client", "Pane", "Server", "Session", "Window")

  test("a Java method is wrapped or named as omitted") {
    val root = repoRoot
    val omissions = readOmissions(root)
    val problems = types.flatMap { name =>
      val java = javaMethods(root.resolve(s"libtmux/src/main/java/io/github/libtmux/$name.java"), name)
      val scala = scalaMembers(
        root.resolve(s"libtmux-scala/src/main/scala/io/github/libtmux/scaladsl/blocking/$name.scala")
      )
      val missing = java.diff(scala).filterNot(method => omissions.contains(s"$name.$method"))
      val stale = omissions.keySet
        .filter(_.startsWith(s"$name."))
        .map(_.stripPrefix(s"$name."))
        .filter(method => !java.contains(method) || scala.contains(method))
      missing.map(method => s"$name.$method is neither wrapped nor listed") ++
        stale.map(method => s"$name.$method is listed but no longer omitted")
    }
    assertEquals(problems, Vector.empty)
  }

  private def javaMethods(file: Path, typeName: String): Set[String] =
    Files.readAllLines(file).asScala.flatMap { line =>
      val method = """^    public (?!static)(.+) (\w+)\(""".r
      method.findFirstMatchIn(line).map(_.group(2)).filter(_ != typeName)
    }.toSet

  private def scalaMembers(file: Path): Set[String] =
    Files.readAllLines(file).asScala.flatMap { line =>
      if (line.trim.startsWith("private")) Nil
      else {
        val member = """^  (?:override )?(?:def|val) (\w+)""".r
        val parameter = """val (\w+):""".r
        val named = member.findFirstMatchIn(line).map(_.group(1)).toList
        val parameters =
          if (line.contains("=")) Nil
          else parameter.findAllMatchIn(line).map(_.group(1)).toList
        named ++ parameters
      }
    }.toSet

  private def readOmissions(root: Path): Map[String, String] = {
    val lines = Files.readAllLines(root.resolve("libtmux-scala/src/test/resources/scala-java-omissions.txt"))
    lines.asScala.filter(line => line.nonEmpty && !line.startsWith("#")).map { line =>
      val space = line.indexOf(' ')
      assert(space > 0, s"omission has no reason: $line")
      line.substring(0, space) -> line.substring(space + 1)
    }.toMap
  }

  private def repoRoot: Path =
    Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(path => Files.isRegularFile(path.resolve("libtmux/src/main/java/io/github/libtmux/Pane.java")))
      .getOrElse(fail(s"repo root not found from ${Path.of("").toAbsolutePath}"))
}
