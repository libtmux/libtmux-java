// The Cats Effect facade: every call reaching tmux runs through Execution under F.interruptible,
// Resources scope servers and subscriptions, and FS2 streams observe without a blocking pool.
import io.github.libtmux.buildlogic.GenerateScalaSources

plugins { id("libtmux.published-scala-library") }

mavenPublishing { pom { description = "Cats Effect resources and FS2 observations for libtmux." } }

dependencies {
    api(project(":libtmux-scala"))
    api(libs.cats.effect)
    api(libs.fs2.core)
}

val generateScalaSources = tasks.register<GenerateScalaSources>("generateScalaSources") {
    description = "Generates the Cats forwards from :libtmux's operation catalog."
    group = "build"
    facade = "cats"
    dependsOn(":libtmux:generateOperationCatalog")
    operationCatalog = project(":libtmux").layout.buildDirectory.file("generated/operation-catalog/operation-catalog.json")
    outputDirectory = layout.buildDirectory.dir("generated/sources/catalog/scala")
}

sourceSets.main { scala.srcDir(generateScalaSources.map { it.outputDirectory }) }
