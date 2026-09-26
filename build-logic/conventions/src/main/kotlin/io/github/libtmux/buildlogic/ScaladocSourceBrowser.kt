package io.github.libtmux.buildlogic

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * An offline source browser for a Scala facade's documentation.
 *
 * Scala 3's scaladoc links a definition to its source only through a URL template. This points the
 * template at a placeholder host, then [complete] writes every source file into `_sources/` as a
 * line-numbered page and rewrites each placeholder link to a relative one. The documentation jar is
 * then browsable without a network, and a generated operation links to the code that actually ships
 * rather than to a file absent from the repository.
 */
object ScaladocSourceBrowser {

    private const val HOST = "libtmux-source.invalid"
    private val HREF = Regex("""(?i)\bhref\s*=\s*("([^"]*)"|'([^']*)')""")
    private val LINE_ANCHOR = Regex("L([1-9][0-9]*)")

    /**
     * The scaladoc option that points source links at [HOST]. Each root is a source directory and the
     * prefix its files take in the browser. The compiler records source paths relative to
     * [sourceRoot] (its `-sourceroot`), so each root is named relative to it too.
     */
    fun options(sourceRoot: File, roots: List<Pair<File, String>>): List<String> = listOf(
        "-source-links:" + roots.joinToString(",") { (root, prefix) ->
            // FILE_PATH_EXT starts with a slash of its own.
            pathString(sourceRoot.toPath().relativize(root.toPath())) + "=https://" + HOST +
                (if (prefix.isEmpty()) "" else "/" + prefix.removeSuffix("/")) + "€{FILE_PATH_EXT}.html#L€{FILE_LINE}"
        },
    )

    /** Writes the browser into [output] and rewrites every placeholder link to point into it. */
    fun complete(output: File, roots: List<Pair<File, String>>, sources: Collection<File>) {
        val sourceRoots = roots.map { (root, prefix) -> root.toPath().toAbsolutePath().normalize() to prefix }
        val browser = output.toPath().resolve("_sources").toAbsolutePath().normalize()
        val pages = output.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".html") && !it.toPath().toAbsolutePath().normalize().startsWith(browser) }
            .toList()
        require(pages.isNotEmpty()) { "scaladoc produced no HTML pages" }
        browser.toFile().deleteRecursively()

        val written = sources.filter { it.name.endsWith(".scala") }.map { file ->
            val path = file.toPath().toAbsolutePath().normalize()
            val (sourceRoot, prefix) = requireNotNull(sourceRoots.firstOrNull { (root, _) -> path.startsWith(root) }) {
                "scaladoc source is outside its module's source roots: $path"
            }
            val relative = prefix + pathString(sourceRoot.relativize(path))
            val bytes = Files.readAllBytes(path)
            val lines = String(bytes, Charsets.UTF_8).replace("\r\n", "\n").split("\n")
            val numbered = lines.withIndex().joinToString("\n") { (index, line) ->
                val number = index + 1
                "<span id=\"L$number\"><a href=\"#L$number\" aria-label=\"Line $number\">$number</a> ${escape(line)}</span>"
            }
            val raw = browser.resolve(relative)
            val title = escape(relative)
            val html = "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">" +
                "<title>$title</title><style>" +
                "body{margin:2rem;font-family:system-ui,sans-serif}pre{overflow:auto}" +
                "pre a{display:inline-block;min-width:4em;color:#666;text-decoration:none}" +
                "pre span:target{background:#fff3bf}" +
                "</style></head><body><h1>$title</h1><p><a href=\"" + escape(uriPath(file.name)) +
                "\">Raw source</a></p><pre><code>$numbered</code></pre></body></html>\n"
            Files.createDirectories(raw.parent)
            Files.write(raw, bytes)
            Files.writeString(browser.resolve("$relative.html"), html)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            Triple(relative, lines.size, hash)
        }.sortedBy { it.first }
        require(written.isNotEmpty()) { "no Scala sources were available for scaladoc" }
        require(written.map { it.first }.distinct().size == written.size) { "duplicate scaladoc source paths" }
        val known = written.associate { (path, lines, _) -> path to lines }
        Files.writeString(browser.resolve("sources.tsv"), written.joinToString("") { (path, _, hash) -> "$path\t$hash\n" })

        fun checkedTarget(path: String, fragment: String?): Path {
            require(path.endsWith(".html")) { "scaladoc source link has no HTML target: $path" }
            val source = path.removeSuffix(".html")
            val lines = requireNotNull(known[source]) { "scaladoc links an unknown source: $source" }
            val line = requireNotNull(LINE_ANCHOR.matchEntire(fragment.orEmpty())) {
                "scaladoc source link has no line anchor: $source"
            }.groupValues[1].toInt()
            require(line <= lines) { "scaladoc source line is outside the file: $source#L$line" }
            val target = browser.resolve(path).normalize()
            require(target.startsWith(browser) && Files.isRegularFile(target)) { "scaladoc source page is missing: $path" }
            require(Files.readString(target).contains("id=\"L$line\"")) { "scaladoc source anchor is missing: $path#L$line" }
            return target
        }

        var sourceLinks = 0
        for (page in pages) {
            val parent = page.toPath().toAbsolutePath().normalize().parent
            val original = page.readText()
            val rewritten = HREF.replace(original) { matched ->
                val value = matched.groups[2]?.value ?: matched.groups[3]!!.value
                if (value.contains(HOST)) {
                    val uri = URI(value)
                    require(uri.scheme == "https" && uri.host == HOST && uri.query == null) { "invalid scaladoc source URL: $value" }
                    val target = checkedTarget(uri.path.removePrefix("/"), uri.fragment)
                    sourceLinks++
                    "href=\"" + escape(uriPath(pathString(parent.relativize(target))) + "#" + uri.fragment) + "\""
                } else {
                    if (value.contains("_sources/")) {
                        val uri = URI(value)
                        require(!uri.isAbsolute && uri.query == null) { "scaladoc source link must be relative: $value" }
                        val target = parent.resolve(uri.path).normalize()
                        require(target.startsWith(browser)) { "scaladoc source link leaves its browser: $value" }
                        checkedTarget(pathString(browser.relativize(target)), uri.fragment)
                        sourceLinks++
                    }
                    matched.value
                }
            }
            require(!rewritten.contains(HOST)) { "a scaladoc source URL was not rewritten in $page" }
            if (rewritten != original) page.writeText(rewritten)
        }
        require(sourceLinks > 0) { "scaladoc emitted no checked source links" }
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun pathString(path: Path): String = path.toString().replace('\\', '/')

    private fun uriPath(path: String): String = URI(null, null, path, null).toASCIIString()
}
