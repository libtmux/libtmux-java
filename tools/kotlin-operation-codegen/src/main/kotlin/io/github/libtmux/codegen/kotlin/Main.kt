package io.github.libtmux.codegen.kotlin

import java.io.File

/**
 * `--catalog <operation-catalog.json> --outputDir <dir>`. Reads the catalog and writes one Kotlin
 * file per owner type into `outputDir`, creating it if needed. Invoked by
 * `:libtmux-kotlin`'s `generateOperationWrappers` Gradle task, never run by hand.
 */
public fun main(args: Array<String>) {
    val options = parseArgs(args)
    val catalog = readCatalog(options.catalogFile)
    val outputDir = options.outputDir
    outputDir.mkdirs()
    val files = generateFiles(catalog)
    for (file in files) {
        file.writeTo(outputDir)
    }
    println("wrote ${files.size} Kotlin file(s) for ${catalog.operations.size} catalogued operation(s)")
}

private data class Options(val catalogFile: File, val outputDir: File)

private fun parseArgs(args: Array<String>): Options {
    var catalog: File? = null
    var outputDir: File? = null
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--catalog" -> catalog = File(args[++index])
            "--outputDir" -> outputDir = File(args[++index])
            else -> throw IllegalArgumentException("unrecognized argument: ${args[index]}")
        }
        index++
    }
    return Options(
        requireNotNull(catalog) { "--catalog is required" },
        requireNotNull(outputDir) { "--outputDir is required" },
    )
}
