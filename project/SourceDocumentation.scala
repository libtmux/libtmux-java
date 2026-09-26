import sbt._
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.security.MessageDigest
import scala.util.matching.Regex

object SourceDocumentation {
  private val host = "libtmux-source.invalid"
  private val href = "(?i)\\bhref\\s*=\\s*(\"([^\"]*)\"|'([^']*)')".r
  private val lineAnchor = "L([1-9][0-9]*)".r

  /** Each root is a source directory and the prefix its files take in the
    * source browser: hand-written sources at the top, generated ones beside
    * them, so a generated operation's documentation links to the code that
    * ships.
    */
  def options(roots: Seq[(File, String)]): Seq[String] =
    Seq(
      "-source-links:" + roots
        .map { case (root, prefix) =>
          // FILE_PATH_EXT starts with a slash of its own.
          root.getAbsolutePath + "=https://" + host +
            (if (prefix.isEmpty) "" else "/" + prefix.stripSuffix("/")) +
            "€{FILE_PATH_EXT}.html#L€{FILE_LINE}"
        }
        .mkString(",")
    )

  private def escape(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")

  private def pathString(path: java.nio.file.Path): String =
    path.toString.replace('\\', '/')

  private def uriPath(path: String): String =
    new URI(null, null, path, null).toASCIIString

  def complete(
      output: File,
      roots: Seq[(File, String)],
      sources: Seq[File]
  ): File = {
    val sourceRoots = roots.map { case (root, prefix) =>
      (root.toPath.toAbsolutePath.normalize(), prefix)
    }
    val browser = (output / "_sources").toPath.toAbsolutePath.normalize()
    val pages = (output ** "*.html").get.filterNot(file =>
      file.toPath.toAbsolutePath.normalize().startsWith(browser)
    )
    require(pages.nonEmpty, "Native Scaladoc produced no HTML pages")
    IO.delete(browser.toFile)
    val sourceLines = sources
      .filter(_.getName.endsWith(".scala"))
      .map { file =>
        val path = file.toPath.toAbsolutePath.normalize()
        val (sourceRoot, prefix) = sourceRoots
          .find { case (root, _) => path.startsWith(root) }
          .getOrElse(
            sys.error(
              "Scaladoc source is outside its module source roots: " + path
            )
          )
        val relative = prefix + pathString(sourceRoot.relativize(path))
        val bytes = Files.readAllBytes(path)
        val text = new String(bytes, UTF_8)
        val lines = text.replace("\r\n", "\n").split("\n", -1).toVector
        val numbered = lines.zipWithIndex
          .map { case (line, index) =>
            val number = index + 1
            "<span id=\"L" + number + "\"><a href=\"#L" + number +
              "\" aria-label=\"Line " + number + "\">" + number + "</a> " +
              escape(line) + "</span>"
          }
          .mkString("\n")
        val raw = browser.resolve(relative)
        val page = browser.resolve(relative + ".html")
        val title = escape(relative)
        val html =
          "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<title>" + title + "</title><style>" +
            "body{margin:2rem;font-family:system-ui,sans-serif}pre{overflow:auto}" +
            "pre a{display:inline-block;min-width:4em;color:#666;text-decoration:none}" +
            "pre span:target{background:#fff3bf}" +
            "</style></head><body><h1>" + title + "</h1><p><a href=\"" +
            escape(uriPath(file.getName)) + "\">Raw source</a></p><pre><code>" +
            numbered + "</code></pre></body></html>\n"
        IO.createDirectory(raw.getParent.toFile)
        Files.write(raw, bytes)
        IO.write(page.toFile, html, UTF_8)
        val hash = MessageDigest
          .getInstance("SHA-256")
          .digest(bytes)
          .map(value => "%02x".format(value & 0xff))
          .mkString
        (relative, lines.size, hash)
      }
      .sortBy(_._1)
    require(
      sourceLines.nonEmpty,
      "No Scala sources were available for Scaladoc"
    )
    require(
      sourceLines.map(_._1).distinct.size == sourceLines.size,
      "Duplicate Scaladoc source paths"
    )
    val known = sourceLines.map { case (path, lines, _) => path -> lines }.toMap
    IO.write(
      browser.resolve("sources.tsv").toFile,
      sourceLines
        .map { case (path, _, hash) => path + "\t" + hash }
        .mkString("", "\n", "\n"),
      UTF_8
    )

    def checkedTarget(path: String, fragment: String): java.nio.file.Path = {
      require(
        path.endsWith(".html"),
        "Scaladoc source link has no HTML target: " + path
      )
      val source = path.stripSuffix(".html")
      require(
        known.contains(source),
        "Scaladoc links an unknown source: " + source
      )
      val line = Option(fragment).getOrElse("") match {
        case lineAnchor(number) => number.toInt
        case _                  =>
          sys.error("Scaladoc source link has no line anchor: " + source)
      }
      require(
        line <= known(source),
        "Scaladoc source line is outside the file: " + source + "#L" + line
      )
      val target = browser.resolve(path).normalize()
      require(
        target.startsWith(browser) && Files.isRegularFile(target),
        "Scaladoc source page is missing: " + path
      )
      require(
        IO.read(target.toFile, UTF_8).contains("id=\"L" + line + "\""),
        "Scaladoc source anchor is missing: " + path + "#L" + line
      )
      target
    }

    var sourceLinkCount = 0
    pages.foreach { page =>
      val parent = page.toPath.toAbsolutePath.normalize().getParent
      val original = IO.read(page, UTF_8)
      val rewritten = href.replaceAllIn(
        original,
        matched => {
          val value = Option(matched.group(2)).getOrElse(matched.group(3))
          if (value.contains(host)) {
            val uri = new URI(value)
            require(
              uri.getScheme == "https" && uri.getHost == host && uri.getQuery == null,
              "Invalid native Scaladoc source URL"
            )
            val target =
              checkedTarget(uri.getPath.stripPrefix("/"), uri.getFragment)
            sourceLinkCount += 1
            val relative = uriPath(
              pathString(parent.relativize(target))
            ) + "#" + uri.getFragment
            Regex.quoteReplacement("href=\"" + escape(relative) + "\"")
          } else {
            if (value.contains("_sources/")) {
              val uri = new URI(value)
              require(
                !uri.isAbsolute && uri.getQuery == null,
                "Scaladoc source link must be relative"
              )
              val target = parent.resolve(uri.getPath).normalize()
              require(
                target.startsWith(browser),
                "Scaladoc source link leaves its browser"
              )
              checkedTarget(
                pathString(browser.relativize(target)),
                uri.getFragment
              )
              sourceLinkCount += 1
            }
            Regex.quoteReplacement(matched.matched)
          }
        }
      )
      require(
        !rewritten.contains(host),
        "Native Scaladoc source URL was not rewritten"
      )
      if (rewritten != original) IO.write(page, rewritten, UTF_8)
    }
    require(
      sourceLinkCount > 0,
      "Native Scaladoc emitted no checked source links"
    )
    output
  }
}
