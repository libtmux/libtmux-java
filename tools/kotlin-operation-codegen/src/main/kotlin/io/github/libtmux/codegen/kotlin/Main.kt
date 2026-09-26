package io.github.libtmux.codegen.kotlin

import java.io.File

/**
 * `--catalog <operation-catalog.json> --outputDir <dir> [--fields <field-catalog.tsv>]`. Writes one
 * Kotlin file per owner type for the operation catalog, and, when `--fields` is given, one more per
 * owner for its companion-hosted query field properties. Invoked by `:libtmux-kotlin`'s
 * `generateOperationWrappers`/`generateFieldProperties` Gradle tasks, never run by hand.
 */
public fun main(args: Array<String>) {
    val options = parseArgs(args)
    options.outputDir.mkdirs()

    val catalog = readCatalog(options.catalogFile)
    val operationFiles = generateFiles(catalog)
    operationFiles.forEach { it.writeTo(options.outputDir) }
    println("wrote ${operationFiles.size} Kotlin file(s) for ${catalog.operations.size} catalogued operation(s)")

    options.fieldsFile?.let { fieldsFile ->
        val rows = readFieldCatalog(fieldsFile)
        val fieldFiles = generateFieldPropertyFiles(rows)
        fieldFiles.forEach { it.writeTo(options.outputDir) }
        println("wrote ${fieldFiles.size} Kotlin file(s) for ${rows.size} catalogued field(s)")
    }
}

private data class Options(val catalogFile: File, val outputDir: File, val fieldsFile: File?)

private fun parseArgs(args: Array<String>): Options {
    var catalog: File? = null
    var outputDir: File? = null
    var fields: File? = null
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--catalog" -> catalog = File(args[++index])
            "--outputDir" -> outputDir = File(args[++index])
            "--fields" -> fields = File(args[++index])
            else -> throw IllegalArgumentException("unrecognized argument: ${args[index]}")
        }
        index++
    }
    return Options(
        requireNotNull(catalog) { "--catalog is required" },
        requireNotNull(outputDir) { "--outputDir is required" },
        fields,
    )
}
