package io.github.libtmux.scaladsl.cats

import java.lang.reflect.{Member, Modifier}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters._

/** The published Scala API, as the JVM sees it, against a committed manifest.
  *
  * Kotlin's ABI dump does this for libtmux-kotlin. Nothing Scala has been
  * released, so there is no jar for MiMa to compare with; this keeps every
  * change to a public signature a line in review. Regenerate after a deliberate
  * change with `LIBTMUX_SCALA_API_WRITE=1` on both Scala versions.
  */
final class ApiManifestSuite extends FunSuite {
  private val modules = Vector(
    "core" -> classOf[io.github.libtmux.scaladsl.blocking.Server],
    "cats" -> classOf[io.github.libtmux.scaladsl.cats.Control[Option]]
  )

  // Scala 3 runs on the 2.13 library, so its version string says 2.13; only
  // Scala 3's own runtime ships LazyVals.
  private val binary =
    try { Class.forName("scala.runtime.LazyVals"); "3" }
    catch { case _: ClassNotFoundException => "2.13" }

  test("every public signature is in the committed manifest") {
    modules.foreach { case (name, anchor) =>
      val file = repoRoot.resolve(s"libtmux-scala/api/$name-$binary.api")
      val actual = manifest(anchor)
      if (sys.env.contains("LIBTMUX_SCALA_API_WRITE")) {
        Files.createDirectories(file.getParent)
        Files.writeString(file, actual)
      } else {
        assert(Files.isRegularFile(file), s"$file is missing")
        assertEquals(
          actual,
          Files.readString(file),
          s"$name's public API changed; review it and regenerate $file"
        )
      }
    }
  }

  private def manifest(anchor: Class[_]): String = {
    val root =
      Path.of(anchor.getProtectionDomain.getCodeSource.getLocation.toURI)
    val loader = anchor.getClassLoader
    val names = Files
      .walk(root)
      .iterator()
      .asScala
      .map(root.relativize(_).toString)
      .filter(_.endsWith(".class"))
      .map(_.stripSuffix(".class").replace('/', '.'))
      .toVector
      .sorted
    names
      .map(Class.forName(_, false, loader))
      .filter(visible)
      .flatMap(describe)
      .mkString("", "\n", "\n")
  }

  private def visible(clazz: Class[_]): Boolean =
    Modifier.isPublic(clazz.getModifiers) && !clazz.isSynthetic &&
      !clazz.getName.contains("$anon") && !clazz.getName.contains("$$")

  private def describe(clazz: Class[_]): Vector[String] = {
    val kind =
      if (clazz.isInterface) "interface"
      else Modifier.toString(clazz.getModifiers & Modifier.FINAL) + " class"
    val parents = (Option(clazz.getSuperclass).toVector ++ clazz.getInterfaces)
      .map(_.getName)
      .filterNot(_ == "java.lang.Object")
    val header = s"${kind.trim} ${clazz.getName}" +
      (if (parents.isEmpty) "" else parents.mkString(" : ", ", ", ""))
    val constructors = clazz.getDeclaredConstructors.toVector
      .filter(shown)
      .map(c =>
        s"  <init>(${c.getParameterTypes.map(_.getTypeName).mkString(", ")})"
      )
    val methods = clazz.getDeclaredMethods.toVector
      .filter(m => shown(m) && !m.isBridge)
      .map(m =>
        s"  ${modifiers(m)}${m.getReturnType.getTypeName} ${m.getName}(${m.getParameterTypes.map(_.getTypeName).mkString(", ")})"
      )
    val fields = clazz.getDeclaredFields.toVector
      .filter(shown)
      .map(f => s"  ${modifiers(f)}${f.getType.getTypeName} ${f.getName}")
    header +: (constructors ++ methods ++ fields).sorted
  }

  private def shown(member: Member): Boolean =
    (Modifier.isPublic(member.getModifiers) || Modifier.isProtected(
      member.getModifiers
    )) && !member.isSynthetic

  private def modifiers(member: Member): String = {
    val kept = member.getModifiers &
      (Modifier.PROTECTED | Modifier.STATIC | Modifier.ABSTRACT)
    val text = Modifier.toString(kept)
    if (text.isEmpty) "" else text + " "
  }

  private def repoRoot: Path =
    Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(path => Files.isRegularFile(path.resolve("build.sbt")))
      .getOrElse(fail("repo root not found"))
}
