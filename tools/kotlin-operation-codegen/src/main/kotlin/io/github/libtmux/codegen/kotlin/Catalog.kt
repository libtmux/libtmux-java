package io.github.libtmux.codegen.kotlin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/** One `@Operation`-bearing method, as `operation-catalog.json` records it. */
public data class CatalogOperation(
    val owner: String,
    val name: String,
    val kind: String,
    val parameters: List<CatalogParameter>,
    val returns: CatalogType,
    val varargs: Boolean = false,
    val summary: String = "",
)

/** One parameter of a [CatalogOperation]. */
public data class CatalogParameter(val name: String, val type: String)

/** A return type, with the schema's own nullability flag alongside the canonical spelling. */
public data class CatalogType(val type: String, val nullable: Boolean = false)

/** The whole catalog: every operation the Doclet found, or a hand-maintained stand-in for it. */
public data class Catalog(val schema: Int, val operations: List<CatalogOperation>)

/**
 * Reads a catalog file matching `operation-catalog-schema.md`.
 *
 * Unset optional fields fall back to the schema's own defaults (`varargs` false, no summary) rather
 * than failing, so a hand-written fixture may omit what it does not need.
 */
public fun readCatalog(file: File): Catalog {
    val root = ObjectMapper().readTree(file)
    val schema = root.path("schema").asInt(1)
    val operations = root.path("operations").map(::readOperation)
    return Catalog(schema, operations)
}

private fun readOperation(node: JsonNode): CatalogOperation {
    val parameters = node.path("parameters").map { param ->
        CatalogParameter(param.path("name").asText(), param.path("type").asText())
    }
    val returns = node.path("returns").let {
        CatalogType(it.path("type").asText("void"), it.path("nullable").asBoolean(false))
    }
    val summary = node.path("javadoc").path("summary").asText("")
    return CatalogOperation(
        owner = node.path("owner").asText(),
        name = node.path("name").asText(),
        kind = node.path("kind").asText(),
        parameters = parameters,
        returns = returns,
        varargs = node.path("varargs").asBoolean(false),
        summary = summary,
    )
}
