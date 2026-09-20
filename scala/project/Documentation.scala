import sbt._
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import scala.collection.mutable.ArrayBuffer

object Documentation {
  private val ignored = Set(
    ".git",
    ".gradle",
    ".bsp",
    ".metals",
    ".idea",
    "target",
    "build",
    "node_modules"
  )
  private val opening = "^ {0,3}(\\x60{3,}|~{3,})[ \\t]*([^\\s\\x60]*).*$".r
  private val unsupportedScalaFence =
    "(?i)^[ \\t>]*(?:\\x60{3,}|~{3,})[ \\t]*(?:scala\\S*|sbt)(?:[ \\t].*)?$".r
  private val directive =
    "^<!--\\s*snippet:\\s*scala-(sync|io|reject|build):\\s*([a-z0-9][a-z0-9-]*)(?:\\s*\\|\\s*(.+?))?\\s*-->$".r

  private final case class Snippet(
      file: File,
      line: Int,
      id: String,
      mode: String,
      detail: String,
      code: String
  ) {
    def name(root: File): String =
      relative(root, file) + ":" + line + " " + id
    def objectName: String = "ScalaDoc_" + id.replace('-', '_')
  }

  private def relative(root: File, file: File): String =
    root.toPath.relativize(file.toPath).toString.replace('\\', '/')

  private def digest(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(value => "%02x".format(value & 0xff))
      .mkString

  private def markdown(root: File): Vector[File] = {
    val found = ArrayBuffer.empty[File]
    Files.walkFileTree(
      root.toPath,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(
            path: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult =
          if (path != root.toPath && ignored(path.getFileName.toString))
            FileVisitResult.SKIP_SUBTREE
          else FileVisitResult.CONTINUE

        override def visitFile(
            path: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult = {
          if (
            attributes.isRegularFile && path.getFileName.toString
              .endsWith(".md")
          )
            found += path.toFile
          FileVisitResult.CONTINUE
        }
      }
    )
    found.toVector.sortBy(file => relative(root, file))
  }

  private def read(root: File): Vector[Snippet] = {
    val found = markdown(root).flatMap { file =>
      val lines = IO.read(file).replace("\r\n", "\n").split("\n", -1).toVector
      val snippets = ArrayBuffer.empty[Snippet]
      val unusedDirectives = scala.collection.mutable.Set.empty[Int]
      var index = 0
      while (index < lines.length) {
        lines(index) match {
          case opening(marker, rawLanguage) =>
            val language = rawLanguage.toLowerCase(java.util.Locale.ROOT)
            val start = index
            index += 1
            val body = index
            while (index < lines.length && !closes(lines(index), marker))
              index += 1
            require(
              index < lines.length,
              relative(
                root,
                file
              ) + ":" + (start + 1) + " has an unclosed fence"
            )
            if (language.startsWith("scala") || language == "sbt") {
              require(
                language == "scala" || language == "sbt",
                relative(
                  root,
                  file
                ) + ":" + (start + 1) + " must use the cross-version scala fence language"
              )
              val beforeIndex = (0 until start).reverseIterator.find(position =>
                lines(position).trim.nonEmpty
              )
              val before =
                beforeIndex.map(position => lines(position).trim).getOrElse("")
              val where = relative(root, file) + ":" + (start + 1)
              before match {
                case directive(mode, id, detail) =>
                  beforeIndex.foreach(unusedDirectives.remove)
                  require(
                    (mode == "build") == (language == "sbt"),
                    where + " must use sbt only for scala-build"
                  )
                  require(
                    (mode == "reject") == Option(detail).exists(
                      _.trim.nonEmpty
                    ),
                    where + " requires a diagnostic phrase exactly for scala-reject"
                  )
                  val code = lines.slice(body, index).mkString("", "\n", "\n")
                  require(code.trim.nonEmpty, where + " contains no code")
                  snippets += Snippet(
                    file,
                    start + 1,
                    id,
                    mode,
                    Option(detail).getOrElse("").trim,
                    code
                  )
                case _ =>
                  sys.error(
                    where + " has an unclassified Scala fence; use a snippet: scala-sync, scala-io, scala-reject or scala-build directive"
                  )
              }
            }
            index += 1
          case _ =>
            require(
              unsupportedScalaFence.findFirstIn(lines(index)).isEmpty,
              relative(
                root,
                file
              ) + ":" + (index + 1) + " Scala fences must be unquoted and indented at most three spaces"
            )
            if (lines(index).trim.startsWith("<!-- snippet: scala-"))
              unusedDirectives += index
            index += 1
        }
      }
      require(
        unusedDirectives.isEmpty,
        relative(
          root,
          file
        ) + " has unused Scala snippet directives on lines " +
          unusedDirectives.toVector.sorted.map(_ + 1).mkString(", ")
      )
      snippets.toVector
    }
    require(
      found.exists(snippet => snippet.mode == "sync" || snippet.mode == "io"),
      "No runnable Scala documentation fences were discovered"
    )
    val duplicates = found.groupBy(_.id).collect {
      case (id, occurrences) if occurrences.size > 1 => id
    }
    require(
      duplicates.isEmpty,
      "Duplicate Scala documentation ids: " + duplicates.toVector.sorted
        .mkString(", ")
    )
    found
  }

  private def closes(line: String, marker: String): Boolean = {
    val trimmed = line.trim
    line.takeWhile(_ == ' ').length <= 3 &&
    trimmed.length >= marker.length && trimmed.forall(_ == marker.head)
  }

  private def quoted(value: String): String = "\"" + value.flatMap {
    case '\\'      => "\\\\"
    case '"'       => "\\\""
    case '\n'      => "\\n"
    case '\r'      => "\\r"
    case '\t'      => "\\t"
    case character => character.toString
  } + "\""

  private def write(file: File, value: String): File = {
    if (!file.isFile || IO.read(file) != value) IO.write(file, value)
    file
  }

  def resources(
      root: File,
      managed: File,
      mains: Seq[String],
      sourceFiles: Seq[File]
  ): Seq[File] = {
    val snippets = read(root)
    val buildDirectory = managed / "scala-doc-build"
    IO.delete(buildDirectory)
    val builds = snippets.filter(_.mode == "build").map { snippet =>
      write(buildDirectory / (snippet.id + ".sbt"), snippet.code)
    }
    val inventory = snippets
      .map { snippet =>
        Vector(
          relative(root, snippet.file),
          snippet.line.toString,
          snippet.id,
          snippet.mode,
          digest(snippet.code.getBytes(UTF_8))
        ).mkString("\t")
      }
      .mkString("", "\n", "\n")
    val sources = sourceFiles
      .sortBy(file => relative(root, file))
      .map { file =>
        relative(root, file) + "\t" + digest(Files.readAllBytes(file.toPath))
      }
      .mkString("", "\n", "\n")
    Vector(
      write(managed / "scala-docs-inventory.tsv", inventory),
      write(
        managed / "example-mains.txt",
        mains.sorted.mkString("", "\n", "\n")
      ),
      write(managed / "example-sources.txt", sources)
    ) ++ builds
  }

  def sources(root: File, managed: File): Seq[File] = {
    val snippets = read(root)
    val runnable = snippets.filterNot(_.mode == "build")
    val documents = snippets
      .map(_.file)
      .distinct
      .map { file =>
        quoted(relative(root, file)) + " -> " + quoted(
          digest(Files.readAllBytes(file.toPath))
        )
      }
      .mkString(",\n")
    val names =
      runnable.map(snippet => quoted(snippet.name(root))).mkString(",\n")
    val declarations = runnable
      .filterNot(_.mode == "reject")
      .map { snippet =>
        val body = if (snippet.mode == "sync") {
          "DocumentationRuntime.requireUnit {\n" + snippet.code + "\n}"
        } else "{\n" + snippet.code + "\n}"
        val result =
          if (snippet.mode == "sync") "Unit" else "_root_.cats.effect.IO[Unit]"
        "private[docs] object " + snippet.objectName + " {\n" +
          "def run(config: io.github.libtmux.ServerConfig): " + result + " = " + body + "\n}\n"
      }
      .mkString("\n")
    val cases = runnable
      .map { snippet =>
        val invoke = snippet.objectName + ".run(fixture.config)"
        val body = snippet.mode match {
          case "sync" =>
            "io.github.libtmux.scaladsl.fixture.OwnedTmux.use { fixture => " + invoke + " }"
          case "io" =>
            "io.github.libtmux.scaladsl.fixture.OwnedTmux.use { fixture =>\n" +
              "val evaluated = new java.util.concurrent.atomic.AtomicBoolean(false)\n" +
              invoke + ".flatMap(_ => _root_.cats.effect.IO(evaluated.set(true)))\n" +
              ".timeout(_root_.scala.concurrent.duration.FiniteDuration(1, java.util.concurrent.TimeUnit.SECONDS))\n" +
              ".unsafeRunSync()(_root_.cats.effect.unsafe.IORuntime.global)\n" +
              "assert(evaluated.get(), \"IO snippet was not evaluated\")\n}"
          case "reject" =>
            val code =
              "{\nval config: io.github.libtmux.ServerConfig = null\n" + snippet.code + "\n}"
            "val errors = compileErrors(" + quoted(code) + ")\n" +
              "assert(errors.nonEmpty, \"snippet unexpectedly compiles\")\n" +
              "assert(errors.contains(" + quoted(snippet.detail) + "), errors)"
        }
        "test(" + quoted(snippet.name(root)) + ") {\n" + body + "\n}\n"
      }
      .mkString("\n")
      .split("\n", -1)
      .map("  " + _)
      .mkString("\n")
    val source =
      """package io.github.libtmux.scaladsl.docs

private[docs] object DocumentationRuntime {
  def requireUnit[A](value: A)(implicit evidence: A =:= Unit): Unit = evidence(value)
  private val ignored = Set(".git", ".gradle", ".bsp", ".metals", ".idea", "target", "build", "node_modules")

  def markdown(root: java.nio.file.Path): Vector[java.nio.file.Path] = {
    val found = Vector.newBuilder[java.nio.file.Path]
    java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor[java.nio.file.Path] {
      override def preVisitDirectory(path: java.nio.file.Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult =
        if (path != root && ignored(path.getFileName.toString)) java.nio.file.FileVisitResult.SKIP_SUBTREE
        else java.nio.file.FileVisitResult.CONTINUE
      override def visitFile(path: java.nio.file.Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult = {
        if (attributes.isRegularFile && path.getFileName.toString.endsWith(".md")) found += path
        java.nio.file.FileVisitResult.CONTINUE
      }
    })
    found.result()
  }

  def fences(file: java.nio.file.Path): Int = {
    val opening = "^ {0,3}(\\x60{3,}|~{3,})[ \\t]*([^\\s\\x60]*).*$".r
    val text = java.nio.file.Files.readString(file).replace("\r\n", "\n")
    var marker = ""
    var count = 0
    text.split("\n", -1).foreach { line =>
      if (marker.nonEmpty) {
        val trimmed = line.trim
        if (line.takeWhile(_ == ' ').length <= 3 && trimmed.length >= marker.length && trimmed.forall(_ == marker.head))
          marker = ""
      } else line match {
        case opening(delimiter, rawLanguage) =>
          val language = rawLanguage.toLowerCase(java.util.Locale.ROOT)
          marker = delimiter
          if (language.startsWith("scala") || language == "sbt") count += 1
        case _ =>
          if (line.matches("(?i)^[ \\t>]*(?:\\x60{3,}|~{3,})[ \\t]*(?:scala\\S*|sbt)(?:[ \\t].*)?$")) count += 1
      }
    }
    count
  }

  def digest(file: java.nio.file.Path): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file))
      .map(value => "%%02x".format(value & 0xff)).mkString
}

%s

final class DocumentationSuite extends munit.FunSuite {
%s

  test("documentation inventory") {
    val root = java.nio.file.Path.of(sys.props("libtmux.scala.docs.root"))
    val expectedDocuments = Map[String, String](%s)
    val expectedNames = Set[String]("documentation inventory", %s)
    val actualDocuments = DocumentationRuntime.markdown(root).flatMap { file =>
      val count = DocumentationRuntime.fences(file)
      if (count == 0) None else Some((root.relativize(file).toString.replace('\\', '/'), file, count))
    }
    assertEquals(actualDocuments.map(_._1).toSet, expectedDocuments.keySet)
    assertEquals(actualDocuments.map(_._3).sum, %d)
    actualDocuments.foreach { case (name, file, _) =>
      assertEquals(DocumentationRuntime.digest(file), expectedDocuments(name), name + " changed after snippet compilation")
    }
    assertEquals(munitTests().map(_.name).toSet, expectedNames)
    assertEquals(munitTests().size, expectedNames.size)
  }
}
""".format(declarations, cases, documents, names, snippets.size)
    Vector(
      write(
        managed / "io" / "github" / "libtmux" / "scaladsl" / "docs" / "DocumentationSuite.scala",
        source
      )
    )
  }
}
