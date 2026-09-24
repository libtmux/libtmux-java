package io.github.libtmux.scaladsl.cats

import io.github.libtmux.scaladsl.blocking
import java.lang.reflect.{Method, Modifier}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters._

/** Every operation the blocking facade offers, the Cats facade offers too, or
  * names as omitted with its reason.
  *
  * Compared by name and parameter count from the compiled classes: the types
  * differ by design, a Cats handle for a blocking one, so the count is what
  * says an overload is missing.
  */
final class ParitySuite extends FunSuite {
  private val pairs: Vector[(String, Class[_], Class[_])] = Vector(
    ("Server", classOf[blocking.Server], classOf[Server[Option]]),
    ("Session", classOf[blocking.Session], classOf[Session[Option]]),
    ("Window", classOf[blocking.Window], classOf[Window[Option]]),
    ("Pane", classOf[blocking.Pane], classOf[Pane[Option]]),
    ("Client", classOf[blocking.Client], classOf[Client[Option]])
  )

  private lazy val omissions: Set[String] = Files
    .readAllLines(
      repoRoot.resolve(
        "libtmux-scala-cats/src/test/resources/cats-blocking-omissions.txt"
      )
    )
    .asScala
    .map(_.takeWhile(_ != '#').trim)
    .filter(_.nonEmpty)
    .toSet

  test("a blocking operation has a Cats form or is named as omitted") {
    val missing = pairs.flatMap { case (name, from, to) =>
      val offered = forms(to)
      forms(from).toVector
        .filterNot(offered.contains)
        .map { case (method, count) => s"$name.$method/$count" }
        .filterNot(omissions.contains)
        .sorted
    }
    assertEquals(missing, Vector.empty)
  }

  test("an omission still names a missing Cats form") {
    val stale = omissions.filter { entry =>
      val (name, method, count) = parse(entry)
      pairs.find(_._1 == name).forall { case (_, from, to) =>
        !forms(from).contains((method, count)) ||
        forms(to).contains((method, count))
      }
    }
    assertEquals(stale.toVector.sorted, Vector.empty)
  }

  /** Public instance operations a class declares, by name and arity. */
  private def forms(clazz: Class[_]): Set[(String, Int)] =
    clazz.getDeclaredMethods.toVector
      .filter((method: Method) =>
        Modifier.isPublic(method.getModifiers) &&
          !Modifier.isStatic(method.getModifiers) &&
          !method.isSynthetic && !method.isBridge &&
          !method.getName.contains("$")
      )
      .map(method => method.getName -> method.getParameterCount)
      .toSet

  private def parse(entry: String): (String, String, Int) = {
    val dot = entry.indexOf('.')
    val slash = entry.lastIndexOf('/')
    (
      entry.substring(0, dot),
      entry.substring(dot + 1, slash),
      entry.substring(slash + 1).toInt
    )
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
      .getOrElse(sys.error("could not find the repository root"))
}
