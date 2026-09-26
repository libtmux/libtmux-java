// The direct-style Scala 3 facade: opaque handles over the Java ones, collections and Option at the
// boundary, and the query DSL's field companions. Blocking calls, like the Java API it wraps.
import io.github.libtmux.buildlogic.GenerateScalaSources

plugins { id("libtmux.published-scala-library") }

mavenPublishing { pom { description = "Scala collections and blocking operations over libtmux for Java." } }

dependencies {
    api(project(":libtmux"))
}

val generateScalaSources = tasks.register<GenerateScalaSources>("generateScalaSources") {
    description = "Generates the direct-style forwards and field companions from :libtmux's catalogs."
    group = "build"
    facade = "direct"
    dependsOn(":libtmux:generateOperationCatalog")
    operationCatalog = project(":libtmux").layout.buildDirectory.file("generated/operation-catalog/operation-catalog.json")
    fieldCatalog = project(":libtmux").layout.projectDirectory.file("src/main/resources/META-INF/io.github.libtmux/field-catalog.tsv")
    outputDirectory = layout.buildDirectory.dir("generated/sources/catalog/scala")
}

sourceSets.main { scala.srcDir(generateScalaSources.map { it.outputDirectory }) }
