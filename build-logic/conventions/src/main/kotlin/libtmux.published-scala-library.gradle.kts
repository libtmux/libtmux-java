// A Scala 3 facade published to Central, as libtmux-scala_3 and so on (libtmux.publication adds the
// binary-version suffix).
//
// The javadoc jar Central requires holds the scaladoc site, with an offline source browser: the
// generated operations are not in the repository, so a "source" link must reach the code that ships.
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import io.github.libtmux.buildlogic.ScaladocSourceBrowser
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
        ) + ScaladocSourceBrowser.options(rootDir, roots) + classes.files.filter { it.isDirectory }.map { it.path }
    })
    doFirst {
        project.delete(output)
        output.get().asFile.mkdirs()
    }
    doLast { ScaladocSourceBrowser.complete(output.get().asFile, roots, sources.files) }
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
