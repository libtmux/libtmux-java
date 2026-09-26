package io.github.libtmux.buildlogic

import io.github.libtmux.codegen.catalog.parseFieldCatalog
import io.github.libtmux.codegen.java.FIELD_CATALOG_OWNERS
import io.github.libtmux.codegen.java.FieldMetamodelWriter
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
 * Regenerates `Pane_`/`Session_`/`Window_`/`Client_` from `field-catalog.tsv`, so the query DSL's
 * field handles are read off one checked-in table instead of hand-mirrored per language.
 */
@CacheableTask
abstract class GenerateFieldMetamodel : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val catalog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val rows = parseFieldCatalog(catalog.get().asFile.readText())
        val outputDir = outputDirectory.get().asFile.toPath()
        for (owner in FIELD_CATALOG_OWNERS) {
            val ownerRows = rows.filter { it.owner == owner }
            require(ownerRows.isNotEmpty()) { "field-catalog.tsv declares no field for owner $owner" }
            FieldMetamodelWriter.write(owner, ownerRows).writeTo(outputDir)
        }
    }
}
