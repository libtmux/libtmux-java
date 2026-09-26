package io.github.libtmux.codegen.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/** One `@Operation`-bearing method, as `operation-catalog.json` records it. */
data class CatalogOperation(
    val owner: String,
    val name: String,
    val kind: String,
    val parameters: List<CatalogParameter>,
    val returns: CatalogType,
    val static: Boolean = false,
    val varargs: Boolean = false,
    val summary: String = "",
)

/** One parameter of a [CatalogOperation], with the schema's nullability flag beside its spelling. */
data class CatalogParameter(val name: String, val type: String, val nullable: Boolean = false)

/** A return type, with the schema's own nullability flag alongside the canonical spelling. */
data class CatalogType(val type: String, val nullable: Boolean = false)

/** The whole catalog: every operation the Doclet found, or a hand-maintained stand-in for it. */
data class Catalog(val schema: Int, val operations: List<CatalogOperation>)

/**
 * Reads a catalog file written by the operation-catalog Doclet.
 *
 * `owner`, `name`, `kind` and `returns` are required; a record missing one fails here rather than
 * generating a forward with a blank name. Optional fields fall back to the schema's defaults
 * (`static` and `varargs` false, no summary, not nullable), so a hand-written fixture may omit what
 * it does not need.
 */
fun readOperationCatalog(file: File): Catalog = parseOperationCatalog(file.readText())

/** As [readOperationCatalog], over the file's text. */
fun parseOperationCatalog(json: String): Catalog {
    val root = ObjectMapper().readTree(json)
    val schema = root.required("schema").also {
        require(it.isInt) { "operation-catalog.json: schema must be a number, found $it" }
    }.asInt()
    return Catalog(schema, root.required("operations").map(::readOperation))
}

private fun readOperation(node: JsonNode): CatalogOperation =
    CatalogOperation(
        owner = node.requiredText("owner"),
        name = node.requiredText("name"),
        kind = node.requiredText("kind"),
        parameters = node.path("parameters").map { param ->
            CatalogParameter(param.requiredText("name"), param.requiredText("type"), param.path("nullable").asBoolean(false))
        },
        returns = node.required("returns").let {
            CatalogType(it.requiredText("type"), it.path("nullable").asBoolean(false))
        },
        static = node.path("static").asBoolean(false),
        varargs = node.path("varargs").asBoolean(false),
        summary = node.path("javadoc").path("summary").asText(""),
    )

private fun JsonNode.required(field: String): JsonNode =
    requireNotNull(get(field)?.takeUnless { it.isNull }) { "operation-catalog.json: missing '$field' in $this" }

private fun JsonNode.requiredText(field: String): String =
    required(field).also { require(it.isTextual) { "operation-catalog.json: '$field' must be a string in $this" } }.asText()
