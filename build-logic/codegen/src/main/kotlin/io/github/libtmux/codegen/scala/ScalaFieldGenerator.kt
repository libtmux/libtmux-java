package io.github.libtmux.codegen.scala

import io.github.libtmux.codegen.catalog.FieldRow

/**
 * Generates `PaneFields`, `SessionFields`, `WindowFields` and `ClientFields` from the field catalog:
 * one `def` per row, forwarding to the Java descriptor (`Pane_.id()`, ...) the same catalog generates.
 *
 * They live in their own package, `io.github.libtmux.scaladsl.generated`, rather than on the
 * handwritten `object Pane`: Scala 3 treats a class and an object as companions only when they share
 * a file, so `Pane.scala`'s `object Pane` re-exports them with `export generated.PaneFields.*`.
 */
object ScalaFieldGenerator {

    private data class Owner(val name: String, val javaAlias: String, val objectName: String)

    private val OWNERS = listOf(
        Owner("Pane", "JavaPane", "PaneFields"),
        Owner("Session", "JavaSession", "SessionFields"),
        Owner("Window", "JavaWindow", "WindowFields"),
        Owner("Client", "JavaClient", "ClientFields"),
    )

    private const val FIELDS = "_root_.io.github.libtmux.scaladsl.query.Fields"

    /** One file: the four Java handle aliases imported once, then one `object ...Fields` per owner. */
    fun combinedFields(rows: List<FieldRow>): String {
        val blocks = OWNERS.joinToString("\n\n") { fieldsObject(it, rows) }
        return """
            |package io.github.libtmux.scaladsl.generated
            |
            |// Generated from field-catalog.tsv by build-logic/codegen (ScalaFieldGenerator). Not checked in.
            |
            |import io.github.libtmux.{
            |  Pane => JavaPane,
            |  Session => JavaSession,
            |  Window => JavaWindow,
            |  Client => JavaClient
            |}
            |
            |$blocks
            |""".trimMargin()
    }

    private fun fieldsObject(owner: Owner, rows: List<FieldRow>): String {
        val matching = rows.filter { it.owner == owner.name }
        require(matching.isNotEmpty()) { "field-catalog.tsv has no rows for owner ${owner.name}" }
        val metamodel = "_root_.io.github.libtmux.${owner.name}_"
        val body = matching.joinToString("\n\n") { row ->
            val type = wrapperType(row, owner.javaAlias)
            val doc = if (row.javadoc.isEmpty()) "" else "  /** ${row.javadoc} */\n"
            "$doc  def ${row.name}: $type =\n    new $type($metamodel.${row.name}())"
        }
        return "object ${owner.objectName} {\n$body\n}"
    }

    private fun wrapperType(row: FieldRow, javaOwner: String): String = when (row.kind) {
        "TEXT" -> "$FIELDS.TextField[$javaOwner]"
        "NUMBER" -> "$FIELDS.NumberField[$javaOwner]"
        "FLAG" -> "$FIELDS.FlagField[$javaOwner]"
        "-" -> {
            val (kind, target) = row.relationTarget()
            when (kind) {
                "to-many" -> "$FIELDS.ToManyRef[$javaOwner, Java$target]"
                else -> "$FIELDS.ToOneRef[$javaOwner, Java$target]"
            }
        }
        else -> throw IllegalArgumentException("field-catalog.tsv kind must be TEXT, NUMBER, FLAG or -: ${row.kind}")
    }
}
