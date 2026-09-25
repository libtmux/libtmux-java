package io.github.libtmux.scaladsl

import java.lang.reflect.{Method, Modifier}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters._

/** Every public method on the five Java handles is wrapped or named here.
  *
  * Read from the compiled classes, not the sources, so an overload counts: each
  * Java form of a wrapped method needs a Scala form taking the same arguments,
  * directly or through default arguments, or its own line in the omissions.
  * The signatures themselves are frozen by `ApiManifestSuite`, in the Cats
  * module's tests, which checks this module as `core` against
  * `libtmux-scala/api/core-*.api`.
  */
final class SurfaceSuite extends FunSuite {
  private val handles: Vector[(String, Class[_], Class[_])] = Vector(
    ("Client", classOf[io.github.libtmux.Client], classOf[blocking.Client]),
    ("Pane", classOf[io.github.libtmux.Pane], classOf[blocking.Pane]),
    ("Server", classOf[io.github.libtmux.Server], classOf[blocking.Server]),
    ("Session", classOf[io.github.libtmux.Session], classOf[blocking.Session]),
    ("Window", classOf[io.github.libtmux.Window], classOf[blocking.Window])
  )

  private lazy val omissions = readOmissions()

  test("a Java method is wrapped or named as omitted") {
    val problems = handles.flatMap { case (name, java, scala) =>
      val javaNames = javaForms(java).keySet
      val scalaNames = scalaForms(scala).keySet
      val missing = (javaNames -- scalaNames)
        .filterNot(method => omissions.contains(s"$name.$method"))
      val stale = omissions.keySet
        .filter(entry => entry.startsWith(s"$name.") && !entry.contains("("))
        .map(_.stripPrefix(s"$name."))
        .filter(method =>
          !javaNames.contains(method) || scalaNames.contains(method)
        )
      missing.toVector.sorted.map(method =>
        s"$name.$method is neither wrapped nor listed"
      ) ++
        stale.toVector.sorted.map(method =>
          s"$name.$method is listed but no longer omitted"
        )
    }
    assertEquals(problems, Vector.empty)
  }

  test("a wrapped Java overload has a Scala form taking the same arguments") {
    val problems = handles.flatMap { case (name, java, scala) =>
      val covered = scalaForms(scala)
      javaForms(java).toVector.sortBy(_._1).flatMap { case (method, forms) =>
        covered.get(method) match {
          case None            => Vector.empty
          case Some(available) =>
            (forms -- available).toVector
              .map(form => s"$name.$method(${form.mkString(", ")})")
              .filterNot(omissions.contains)
              .sorted
              .map(missing => s"$missing has no Scala form")
        }
      }
    }
    assertEquals(problems, Vector.empty)
  }

  test("an overload listed as omitted still lacks a Scala form") {
    val stale = omissions.keySet.filter(_.contains("(")).filter { entry =>
      handles.exists { case (name, java, scala) =>
        entry.startsWith(s"$name.") && {
          val (method, form) = parse(entry.stripPrefix(s"$name."))
          !javaForms(java).get(method).exists(_.contains(form)) ||
          scalaForms(scala).get(method).exists(_.contains(form))
        }
      }
    }
    assertEquals(stale.toVector.sorted, Vector.empty)
  }

  /** Public instance methods the Java handle declares, as Scala would type
    * them.
    */
  private def javaForms(java: Class[_]): Map[String, Set[Vector[String]]] =
    java.getDeclaredMethods.toVector
      .filter(method =>
        Modifier.isPublic(method.getModifiers) &&
          !Modifier.isStatic(method.getModifiers) &&
          !method.isSynthetic && !method.isBridge
      )
      .groupBy(_.getName)
      .map { case (method, forms) =>
        method -> forms.map(_.getParameterTypes.toVector.map(asScala)).toSet
      }

  /** Every parameter list a Scala caller can use, default arguments included.
    */
  private def scalaForms(scala: Class[_]): Map[String, Set[Vector[String]]] = {
    val all = scala.getMethods.toVector
    val defaults = all
      .map(_.getName)
      .filter(_.contains("$default$"))
      .groupBy(_.takeWhile(_ != '$'))
      .map { case (method, getters) => method -> getters.size }
    all
      .filter(method =>
        !Modifier.isStatic(method.getModifiers) && !method.isSynthetic &&
          !method.isBridge && !method.getName.contains("$")
      )
      .groupBy(_.getName)
      .map { case (method, forms) =>
        method -> forms.flatMap { (form: Method) =>
          val types = form.getParameterTypes.toVector.map(normalized)
          val shortest = (types.size - defaults.getOrElse(method, 0)).max(0)
          (shortest to types.size).map(types.take)
        }.toSet
      }
  }

  private lazy val facades: Map[String, String] =
    handles.map { case (_, java, scala) => java.getName -> scala.getName }.toMap

  /** The type a Scala facade takes where Java takes this one. */
  private def asScala(java: Class[_]): String = java.getName match {
    case "java.util.List" | "java.util.Collection" | "java.lang.Iterable" |
        "[Ljava.lang.String;" =>
      "Seq"
    case "java.util.Optional" => "Option"
    case "java.lang.Runnable" => "Function0"
    case "java.util.function.Consumer" | "java.util.function.Predicate" =>
      "Function1"
    case other => simple(facades.getOrElse(other, other))
  }

  private def normalized(scala: Class[_]): String = scala.getName match {
    case "scala.collection.immutable.Seq" |
        "scala.collection.immutable.Vector" |
        "scala.collection.immutable.IndexedSeq" | "scala.collection.Seq" =>
      "Seq"
    case "scala.Option"    => "Option"
    case "scala.Function0" => "Function0"
    case "scala.Function1" => "Function1"
    case other             => simple(other)
  }

  private def simple(name: String): String =
    name.substring(name.lastIndexOf('.') + 1)

  private def parse(entry: String): (String, Vector[String]) = {
    val open = entry.indexOf('(')
    val inside = entry.substring(open + 1, entry.length - 1)
    (
      entry.substring(0, open),
      if (inside.isEmpty) Vector.empty else inside.split(", ").toVector
    )
  }

  private def readOmissions(): Map[String, String] = {
    val lines = Files.readAllLines(
      repoRoot.resolve(
        "libtmux-scala/src/test/resources/scala-java-omissions.txt"
      )
    )
    lines.asScala
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .map { line =>
        val split =
          if (line.contains("(")) line.indexOf(") ") + 1 else line.indexOf(' ')
        assert(split > 0, s"omission has no reason: $line")
        line.substring(0, split) -> line.substring(split + 1)
      }
      .toMap
  }

  private def repoRoot: Path =
    Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(path =>
        Files.isRegularFile(
          path.resolve("libtmux/src/main/java/io/github/libtmux/Pane.java")
        )
      )
      .getOrElse(
        fail(s"repo root not found from ${Path.of("").toAbsolutePath}")
      )
}
