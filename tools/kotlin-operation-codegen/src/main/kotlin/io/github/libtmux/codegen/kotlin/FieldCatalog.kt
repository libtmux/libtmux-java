package io.github.libtmux.codegen.kotlin

import java.io.File

/** One row of `field-catalog.tsv`: `name owner kind tmuxFormat since relation accessor javadoc`. */
public data class FieldRow(
    val name: String,
    val owner: String,
    val kind: String,
    val relation: String,
    val javadoc: String,
)

/**
 * Reads `field-catalog.tsv`, skipping its `#`-comment lines (including the header) and blank lines.
 * The same file the build's `libtmux.field-catalog` plugin validates and generates `Pane_`/etc. from;
 * this reads it independently rather than depending on that plugin's own model classes.
 */
public fun readFieldCatalog(file: File): List<FieldRow> =
    file.readLines()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val columns = line.split("\t")
            require(columns.size == 8) { "expected 8 tab-separated columns, found ${columns.size}: $line" }
            FieldRow(
                name = columns[0],
                owner = columns[1],
                kind = columns[2],
                relation = columns[5],
                javadoc = columns[7],
            )
        }
