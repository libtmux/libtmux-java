package io.github.libtmux.codegen.catalog

/** The handle types a field or a relation may belong to or target. */
private val OWNERS = setOf("Pane", "Session", "Window", "Client")

/** The [FieldRow.kind] values a scalar field may declare. */
private val SCALAR_KINDS = setOf("TEXT", "NUMBER", "FLAG")

private val RELATION = Regex("^(to-many|to-one):([A-Za-z]+)$")

/**
 * One row of `field-catalog.tsv`: a queryable tmux field, or a relation to another handle type,
 * generated onto `<owner>_`.
 *
 * @param kind `TEXT`, `NUMBER` or `FLAG` for a scalar field; `-` for a relation
 * @param tmuxFormat the tmux format id a scalar field reads; `-` for a relation
 * @param relation `-` for a scalar field; otherwise `to-many:<Type>` or `to-one:<Type>`
 * @param accessor a Java expression over the owner type — a lambda or a method reference
 */
data class FieldRow(
    val name: String,
    val owner: String,
    val kind: String,
    val tmuxFormat: String,
    val since: String,
    val relation: String,
    val accessor: String,
    val javadoc: String,
) {
    val isRelation: Boolean get() = relation != "-"

    /** The relation kind (`to-many`/`to-one`) and target owner, valid only when [isRelation]. */
    fun relationTarget(): Pair<String, String> {
        val match = requireNotNull(RELATION.matchEntire(relation)) { "not a relation: $relation" }
        return match.groupValues[1] to match.groupValues[2]
    }
}

/**
 * Parses and validates `field-catalog.tsv`'s text, failing on the first malformed row: an unknown
 * owner, a field name duplicated on the same owner, or a kind/relation that does not parse.
 */
fun parseFieldCatalog(text: String): List<FieldRow> {
    val seen = mutableSetOf<Pair<String, String>>()
    val rows = mutableListOf<FieldRow>()
    text.lineSequence().forEachIndexed { index, rawLine ->
        val lineNumber = index + 1
        val line = rawLine.trimEnd()
        if (line.isBlank() || line.startsWith("#")) {
            return@forEachIndexed
        }
        val columns = line.split("\t")
        require(columns.size == 8) {
            "field-catalog.tsv:$lineNumber: expected 8 tab-separated columns, found ${columns.size}: $line"
        }
        val name = columns[0]
        val owner = columns[1]
        val kind = columns[2]
        val tmuxFormat = columns[3]
        val since = columns[4]
        val relation = columns[5]
        val accessor = columns[6]
        val javadoc = columns[7]

        require(owner in OWNERS) {
            "field-catalog.tsv:$lineNumber: unknown owner '$owner', must be one of $OWNERS"
        }
        require(seen.add(owner to name)) {
            "field-catalog.tsv:$lineNumber: duplicate field '$owner.$name'"
        }
        if (relation == "-") {
            require(kind in SCALAR_KINDS) {
                "field-catalog.tsv:$lineNumber: bad kind '$kind' for scalar field '$owner.$name'," +
                    " must be one of $SCALAR_KINDS"
            }
        } else {
            require(kind == "-") {
                "field-catalog.tsv:$lineNumber: relation field '$owner.$name' must declare kind '-', found '$kind'"
            }
            val match = RELATION.matchEntire(relation)
            requireNotNull(match) {
                "field-catalog.tsv:$lineNumber: bad relation '$relation' for '$owner.$name'," +
                    " expected 'to-many:<Type>' or 'to-one:<Type>'"
            }
            require(match.groupValues[2] in OWNERS) {
                "field-catalog.tsv:$lineNumber: unknown relation target '${match.groupValues[2]}'" +
                    " for '$owner.$name'"
            }
        }
        rows += FieldRow(name, owner, kind, tmuxFormat, since, relation, accessor, javadoc)
    }
    return rows
}

/** Reads and validates a `field-catalog.tsv` file (see [parseFieldCatalog]). */
fun readFieldCatalog(file: java.io.File): List<FieldRow> = parseFieldCatalog(file.readText())
