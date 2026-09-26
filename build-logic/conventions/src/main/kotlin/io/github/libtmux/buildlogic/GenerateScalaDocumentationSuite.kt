package io.github.libtmux.buildlogic

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Turns every Scala fence in the repository's Markdown into a munit test, so a documented example
 * cannot stop compiling or running unnoticed.
 *
 * Each fence carries a directive on the line before it, `<!-- snippet: scala-MODE: id | detail -->`:
 * `sync` runs the code against a fresh tmux, `io` runs a Cats `IO` to completion within the
 * 15-second deadline every documented snippet has, `reject` asserts the code fails to compile with
 * `detail` in the error, and `build` marks an sbt build fragment the consumer checks exercise
 * instead. An unclassified fence fails the build, and so
 * does a directive no fence follows. A final inventory test pins each document's digest and fence
 * count, so a document edited after the suite was generated cannot pass on the old code.
 */
@CacheableTask
abstract class GenerateScalaDocumentationSuite : DefaultTask() {

    /** The repository root; every Markdown file under it is read. */
    @get:Internal
    abstract val root: DirectoryProperty

    /** The Markdown files, as inputs; derived from [root]. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val markdown: ConfigurableFileTree = project.fileTree(project.rootDir) {
        include("**/*.md")
        exclude(IGNORED.map { "**/$it/**" })
    }

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private data class Snippet(val file: File, val line: Int, val id: String, val mode: String, val detail: String, val code: String) {
        fun name(root: File): String = relative(root, file) + ":" + line + " " + id
        val objectName: String get() = "ScalaDoc_" + id.replace('-', '_')
    }

    @TaskAction
    fun generate() {
        val rootDir = root.get().asFile
        val snippets = read(rootDir)
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val file = output.resolve("io/github/libtmux/scaladsl/docs/DocumentationSuite.scala")
        file.parentFile.mkdirs()
        file.writeText(source(rootDir, snippets))
    }

    private fun read(root: File): List<Snippet> {
        val found = markdownFiles(root).flatMap { file ->
            val lines = file.readText().replace("\r\n", "\n").split("\n")
            val snippets = mutableListOf<Snippet>()
            val unusedDirectives = mutableSetOf<Int>()
            var index = 0
            while (index < lines.size) {
                val opened = OPENING.matchEntire(lines[index])
                if (opened != null) {
                    val marker = opened.groupValues[1]
                    val language = opened.groupValues[2].lowercase(Locale.ROOT)
                    val start = index
                    index++
                    val body = index
                    while (index < lines.size && !closes(lines[index], marker)) index++
                    val where = relative(root, file) + ":" + (start + 1)
                    require(index < lines.size) { "$where has an unclosed fence" }
                    if (language.startsWith("scala") || language == "sbt") {
                        require(language == "scala" || language == "sbt") { "$where must use the scala fence language" }
                        val beforeIndex = (start - 1 downTo 0).firstOrNull { lines[it].isNotBlank() }
                        val before = beforeIndex?.let { lines[it].trim() }.orEmpty()
                        val directive = requireNotNull(DIRECTIVE.matchEntire(before)) {
                            "$where has an unclassified Scala fence; use a snippet: scala-sync, scala-io, scala-reject or scala-build directive"
                        }
                        unusedDirectives.remove(beforeIndex)
                        val (mode, id, detail) = directive.destructured
                        require((mode == "build") == (language == "sbt")) { "$where must use sbt only for scala-build" }
                        require((mode == "reject") == detail.isNotBlank()) { "$where requires a diagnostic phrase exactly for scala-reject" }
                        val code = lines.subList(body, index).joinToString("\n", postfix = "\n")
                        require(code.isNotBlank()) { "$where contains no code" }
                        snippets += Snippet(file, start + 1, id, mode, detail.trim(), code)
                    }
                    index++
                } else {
                    require(!UNSUPPORTED_SCALA_FENCE.containsMatchIn(lines[index])) {
                        relative(root, file) + ":" + (index + 1) + " Scala fences must be unquoted and indented at most three spaces"
                    }
                    if (lines[index].trim().startsWith("<!-- snippet: scala-")) unusedDirectives += index
                    index++
                }
            }
            require(unusedDirectives.isEmpty()) {
                relative(root, file) + " has unused Scala snippet directives on lines " + unusedDirectives.sorted().map { it + 1 }
            }
            snippets
        }
        require(found.any { it.mode == "sync" || it.mode == "io" }) { "no runnable Scala documentation fences were discovered" }
        val duplicates = found.groupBy { it.id }.filterValues { it.size > 1 }.keys.sorted()
        require(duplicates.isEmpty()) { "duplicate Scala documentation ids: $duplicates" }
        return found
    }

    private fun source(root: File, snippets: List<Snippet>): String {
        val runnable = snippets.filterNot { it.mode == "build" }
        val documents = snippets.map { it.file }.distinct()
            .joinToString(",\n") { quoted(relative(root, it)) + " -> " + quoted(digest(it.readBytes())) }
        val names = runnable.joinToString(",\n") { quoted(it.name(root)) }
        val declarations = runnable.filterNot { it.mode == "reject" }.joinToString("\n") { snippet ->
            val body = if (snippet.mode == "sync") "DocumentationRuntime.requireUnit {\n${snippet.code}\n}" else "{\n${snippet.code}\n}"
            val result = if (snippet.mode == "sync") "Unit" else "_root_.cats.effect.IO[Unit]"
            "private[docs] object ${snippet.objectName} {\ndef run(config: io.github.libtmux.ServerConfig): $result = $body\n}\n"
        }
        val cases = runnable.joinToString("\n") { snippet ->
            val invoke = snippet.objectName + ".run(fixture.config)"
            val body = when (snippet.mode) {
                "sync" -> "io.github.libtmux.scaladsl.fixture.OwnedTmux.use { fixture => $invoke }"
                "io" ->
                    "io.github.libtmux.scaladsl.fixture.OwnedTmux.use { fixture =>\n" +
                        "val evaluated = new java.util.concurrent.atomic.AtomicBoolean(false)\n" +
                        "$invoke.flatMap(_ => _root_.cats.effect.IO(evaluated.set(true)))\n" +
                        ".timeout(_root_.scala.concurrent.duration.FiniteDuration(15, java.util.concurrent.TimeUnit.SECONDS))\n" +
                        ".unsafeRunSync()(using _root_.cats.effect.unsafe.IORuntime.global)\n" +
                        "assert(evaluated.get(), \"IO snippet was not evaluated\")\n}"
                else -> {
                    val code = "{\nval config: io.github.libtmux.ServerConfig = null\n${snippet.code}\n}"
                    "val errors = compileErrors(${quoted(code)})\n" +
                        "assert(errors.nonEmpty, \"snippet unexpectedly compiles\")\n" +
                        "assert(errors.contains(${quoted(snippet.detail)}), errors)"
                }
            }
            "test(${quoted(snippet.name(root))}) {\n$body\n}\n"
        }.split("\n").joinToString("\n") { "  $it" }
        return RUNTIME.trimStart() + "\n" + declarations + "\n\n" + """
final class DocumentationSuite extends munit.FunSuite {
$cases

  test("documentation inventory") {
    val root = java.nio.file.Path.of(sys.props("libtmux.scala.docs.root"))
    val expectedDocuments = Map[String, String]($documents)
    val expectedNames = Set[String]("documentation inventory", $names)
    val actualDocuments = DocumentationRuntime.markdown(root).flatMap { file =>
      val count = DocumentationRuntime.fences(file)
      if (count == 0) None else Some((root.relativize(file).toString.replace('\\', '/'), file, count))
    }
    assertEquals(actualDocuments.map(_._1).toSet, expectedDocuments.keySet)
    assertEquals(actualDocuments.map(_._3).sum, ${snippets.size})
    actualDocuments.foreach { case (name, file, _) =>
      assertEquals(DocumentationRuntime.digest(file), expectedDocuments(name), name + " changed after snippet compilation")
    }
    assertEquals(munitTests().map(_.name).toSet, expectedNames)
    assertEquals(munitTests().size, expectedNames.size)
  }
}
"""
    }

    private companion object {
        val IGNORED = setOf(".git", ".gradle", ".bsp", ".metals", ".idea", "target", "build", "node_modules")
        val OPENING = Regex("^ {0,3}(`{3,}|~{3,})[ \\t]*([^\\s`]*).*$")
        val UNSUPPORTED_SCALA_FENCE = Regex("(?i)^[ \\t>]*(?:`{3,}|~{3,})[ \\t]*(?:scala\\S*|sbt)(?:[ \\t].*)?$")
        val DIRECTIVE = Regex("^<!--\\s*snippet:\\s*scala-(sync|io|reject|build):\\s*([a-z0-9][a-z0-9-]*)(?:\\s*\\|\\s*(.+?))?\\s*-->$")

        fun relative(root: File, file: File): String = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun closes(line: String, marker: String): Boolean {
            val trimmed = line.trim()
            return line.takeWhile { it == ' ' }.length <= 3 && trimmed.length >= marker.length && trimmed.all { it == marker[0] }
        }

        fun quoted(value: String): String = "\"" + value.map {
            when (it) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> it.toString()
            }
        }.joinToString("") + "\""

        /** Every Markdown file under [root], skipping build output and nested checkouts (worktrees). */
        fun markdownFiles(root: File): List<File> {
            val found = mutableListOf<File>()
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult =
                    if (path != root.toPath() && (path.fileName.toString() in IGNORED || Files.exists(path.resolve(".git")))) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }

                override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isRegularFile && path.fileName.toString().endsWith(".md")) found += path.toFile()
                    return FileVisitResult.CONTINUE
                }
            })
            return found.sortedBy { relative(root, it) }
        }

        const val RUNTIME = """
package io.github.libtmux.scaladsl.docs

private[docs] object DocumentationRuntime {
  def requireUnit[A](value: A)(implicit evidence: A =:= Unit): Unit = evidence(value)
  private val ignored = Set(".git", ".gradle", ".bsp", ".metals", ".idea", "target", "build", "node_modules")

  def markdown(root: java.nio.file.Path): Vector[java.nio.file.Path] = {
    val found = Vector.newBuilder[java.nio.file.Path]
    java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor[java.nio.file.Path] {
      override def preVisitDirectory(path: java.nio.file.Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult =
        if (!path.equals(root) && (ignored(path.getFileName.toString) || java.nio.file.Files.exists(path.resolve(".git")))) java.nio.file.FileVisitResult.SKIP_SUBTREE
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
      .map(value => "%02x".format(value & 0xff)).mkString
}
"""
    }
}
