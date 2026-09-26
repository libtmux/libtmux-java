package io.github.libtmux.buildlogic

import io.github.libtmux.codegen.catalog.readFieldCatalog
import io.github.libtmux.codegen.catalog.readOperationCatalog
import io.github.libtmux.codegen.scala.ScalaFieldGenerator
import io.github.libtmux.codegen.scala.ScalaOperationGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates a Scala facade's catalog-driven sources: `operations/GeneratedOperations.scala`, and for
 * the direct-style facade `fields/GeneratedFields.scala`. Each is one file because Scala 3 requires
 * same-named top-level definitions to share a compilation unit.
 */
@CacheableTask
abstract class GenerateScalaSources : DefaultTask() {

    /** `direct` for libtmux-scala's extensions, `cats` for libtmux-scala-cats's forwards. */
    @get:Input
    abstract val facade: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val operationCatalog: RegularFileProperty

    /** Set only for the direct-style facade, which hosts the query DSL's field companions. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fieldCatalog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val catalog = readOperationCatalog(operationCatalog.get().asFile)
        val operations = when (val which = facade.get()) {
            "direct" -> ScalaOperationGenerator.combinedDirectStyle(catalog)
            "cats" -> ScalaOperationGenerator.combinedCatsForwards(catalog)
            else -> throw IllegalArgumentException("facade must be direct or cats, not $which")
        }
        root.resolve("operations").apply { mkdirs() }.resolve("GeneratedOperations.scala").writeText(operations)
        if (fieldCatalog.isPresent) {
            val fields = ScalaFieldGenerator.combinedFields(readFieldCatalog(fieldCatalog.get().asFile))
            root.resolve("fields").apply { mkdirs() }.resolve("GeneratedFields.scala").writeText(fields)
        }
    }
}
