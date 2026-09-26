package io.github.libtmux.buildlogic

import io.github.libtmux.codegen.catalog.readFieldCatalog
import io.github.libtmux.codegen.catalog.readOperationCatalog
import io.github.libtmux.codegen.kotlin.generateFieldPropertyFiles
import io.github.libtmux.codegen.kotlin.generateFiles
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates libtmux-kotlin's suspend mirror of every catalogued operation, and the companion-hosted
 * query field properties, with KotlinPoet: one file per owner type for each.
 */
@CacheableTask
abstract class GenerateKotlinOperations : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val operationCatalog: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fieldCatalog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val outputDir = outputDirectory.get().asFile
        // Regeneration would otherwise be additive: an operation the catalog stopped naming would
        // leave its generated function behind.
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        generateFiles(readOperationCatalog(operationCatalog.get().asFile)).forEach { it.writeTo(outputDir) }
        generateFieldPropertyFiles(readFieldCatalog(fieldCatalog.get().asFile)).forEach { it.writeTo(outputDir) }
    }
}
