// Wires field-catalog.tsv into the Java field metamodel it generates. Applied by :libtmux only:
// Pane_/Session_/Window_/Client_ are that module's own query DSL, not a shape another module emits.
import com.diffplug.gradle.spotless.SpotlessExtension
import io.github.libtmux.buildlogic.catalog.GenerateFieldMetamodel

val generateFieldMetamodel =
    tasks.register<GenerateFieldMetamodel>("generateFieldMetamodel") {
        group = "build"
        description = "Generates Pane_/Session_/Window_/Client_ from field-catalog.tsv."
        catalog.set(layout.projectDirectory.file("src/main/resources/META-INF/io.github.libtmux/field-catalog.tsv"))
        outputDirectory.set(layout.buildDirectory.dir("generated/sources/fieldCatalog/java/main"))
    }

extensions.getByType<JavaPluginExtension>().sourceSets.named("main") {
    java.srcDir(generateFieldMetamodel.map { it.outputDirectory })
}

// Generated, not authored: spotless polices style choices a human made, which nobody made here.
extensions.configure<SpotlessExtension> { java { targetExclude("build/generated/sources/fieldCatalog/**") } }
