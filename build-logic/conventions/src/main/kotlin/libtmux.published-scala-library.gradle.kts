// A Scala 3 facade published to Central, as libtmux-scala_3 and so on (libtmux.publication adds the
// binary-version suffix).
//
// The javadoc jar Central requires holds the scaladoc site, with an offline source browser: the
// generated operations are not in the repository, so a "source" link must reach the code that ships.
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import io.github.libtmux.buildlogic.ScaladocSourceBrowser
import java.io.FileOutputStream
import java.io.OutputStream
import org.gradle.api.publish.PublishingExtension

plugins {
    id("libtmux.scala-library")
    id("libtmux.publication")
    id("libtmux.sbom")
}

mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.None(), sourcesJar = SourcesJar.Sources()))
}

val sourceSets = extensions.getByType<SourceSetContainer>()
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Scala 3's scaladoc, run directly. Gradle's ScalaDoc task hands it an argument file its own parser
// misreads once the source-link template is in it, so this passes plain arguments, as the operation
// catalog's Doclet is run too.
val scaladocTool = configurations.create("scaladocTool") { isCanBeConsumed = false }
dependencies {
    scaladocTool("org.scala-lang:scaladoc_3:${libs.findVersion("scala").orElseThrow().requiredVersion}")
}

val scaladocSite = tasks.register<JavaExec>("scaladocSite") {
    description = "Generates the scaladoc site, with an offline browser for every source it documents."
    group = "documentation"
    val main = sourceSets["main"]
    val output = layout.buildDirectory.dir("docs/scaladoc")
    val classes = main.output.classesDirs
    // Configured when the task is realized, after the module script has added its generated sources,
    // so every source root takes part in the browser.
    val roots = main.scala.srcDirs.map { it to "" }
    val sources = main.scala
    inputs.files(sources).withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(classes).withPropertyName("classes").withNormalizer(ClasspathNormalizer::class.java)
    inputs.files(main.compileClasspath).withPropertyName("documentedClasspath").withNormalizer(ClasspathNormalizer::class.java)
    outputs.dir(output)
    classpath = scaladocTool
    mainClass = "dotty.tools.scaladoc.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-d", output.get().asFile.path,
            "-classpath", main.compileClasspath.asPath,
            "-sourceroot", rootDir.path,
            "-project", project.name,
            "-project-version", version.toString(),
            // Java types link to their Javadoc: the core's by the build path that holds its classes, the
            // JDK's and the Scala library's by the package path scaladoc reports for them.
            "-external-mappings:" + listOf(
                ".*/libtmux/build/.*::javadoc::https://javadoc.io/doc/io.github.libtmux/libtmux/$version/",
                ".*java[.]base.*::javadoc::https://docs.oracle.com/en/java/javase/25/docs/api/java.base/",
                "^scala/.*::scaladoc3::https://scala-lang.org/api/3.x/",
            ).joinToString(","),
        ) + ScaladocSourceBrowser.options(rootDir, roots) + classes.files.filter { it.isDirectory }.map { it.path }
    })
    // scaladoc reports an unresolved [[link]] as a warning and exits 0, and ignores -Werror, so its
    // output is kept and read back: a link that renders as plain text fails here, not in review.
    val report = layout.buildDirectory.file("tmp/scaladocSite/output.txt")
    doFirst {
        project.delete(output)
        output.get().asFile.mkdirs()
        val kept = FileOutputStream(report.get().asFile.apply { parentFile.mkdirs() })
        standardOutput = Tee(System.out, kept)
        errorOutput = Tee(System.err, kept)
    }
    doLast {
        standardOutput.close()
        val unresolved = report.get().asFile.readLines().filter { "resolve a member for the given link query" in it }
        check(unresolved.isEmpty()) { "scaladoc could not resolve ${unresolved.size} link(s): $unresolved" }
        ScaladocSourceBrowser.complete(output.get().asFile, roots, sources.files)
    }
}

tasks.named<ScalaDoc>("scaladoc") {
    description = "Replaced by scaladocSite, which this module publishes."
    enabled = false
}

val scaladocJar = tasks.register<Jar>("scaladocJar") {
    description = "The scaladoc site, as the javadoc jar Central requires."
    archiveClassifier = "javadoc"
    from(scaladocSite)
}

the<PublishingExtension>().publications.withType<MavenPublication>().configureEach { artifact(scaladocJar) }

// Documentation is a build output like any other: a broken source link fails the gate, not a release.
tasks.named("check") { dependsOn(scaladocJar) }

/** Writes to the console and to [kept]; closing it closes only [kept]. */
class Tee(private val console: OutputStream, private val kept: OutputStream) : OutputStream() {
    override fun write(b: Int) {
        console.write(b)
        kept.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        console.write(b, off, len)
        kept.write(b, off, len)
    }

    override fun flush() {
        console.flush()
        kept.flush()
    }

    override fun close() = kept.close()
}
