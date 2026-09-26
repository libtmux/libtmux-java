package io.github.libtmux.codegen.scala

import io.github.libtmux.codegen.catalog.FieldRow
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScalaFieldGeneratorTest {

    private fun row(name: String, owner: String, kind: String, relation: String = "-", javadoc: String = "") =
        FieldRow(name, owner, kind, if (kind == "-") "-" else name, "3.2", relation, "x", javadoc)

    private val rows = listOf(
        row("id", "Pane", "TEXT", javadoc = "The pane id."),
        row("windows", "Session", "-", relation = "to-many:Window"),
        row("active", "Window", "FLAG"),
        row("width", "Client", "NUMBER"),
    )

    @Test
    fun `each row forwards to the Java descriptor with its wrapper type`() {
        val source = ScalaFieldGenerator.combinedFields(rows)
        assertTrue(source.contains("  /** The pane id. */\n  def id: _root_.io.github.libtmux.scaladsl.query.Fields.TextField[JavaPane] =\n    new _root_.io.github.libtmux.scaladsl.query.Fields.TextField[JavaPane](_root_.io.github.libtmux.Pane_.id())"), source)
        assertTrue(source.contains("def windows: _root_.io.github.libtmux.scaladsl.query.Fields.ToManyRef[JavaSession, JavaWindow] ="), source)
        assertTrue(source.contains("def active: _root_.io.github.libtmux.scaladsl.query.Fields.FlagField[JavaWindow] ="), source)
    }

    @Test
    fun `an owner with no rows fails`() {
        assertThrows<IllegalArgumentException> { ScalaFieldGenerator.combinedFields(rows.filterNot { it.owner == "Client" }) }
    }
}
