package io.github.libtmux.codegen.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FieldCatalogTest {

    private val validRow = "id\tPane\tTEXT\tpane_id\t1.7\t-\tPane::currentCommand\tThe pane id."

    @Test
    fun `parses a well-formed row`() {
        val rows = parseFieldCatalog(validRow)

        assertEquals(1, rows.size)
        assertEquals(FieldRow("id", "Pane", "TEXT", "pane_id", "1.7", "-", "Pane::currentCommand", "The pane id."), rows[0])
    }

    @Test
    fun `parses a relation row`() {
        val rows = parseFieldCatalog("panes\tWindow\t-\t-\t-\tto-many:Pane\tWindow::panes\tThis link's panes.")

        assertEquals("to-many" to "Pane", rows[0].relationTarget())
    }

    @Test
    fun `ignores comment and blank lines`() {
        val rows = parseFieldCatalog("# a comment\n\n$validRow\n")

        assertEquals(1, rows.size)
    }

    @Test
    fun `rejects an unknown owner`() {
        val bad = "id\tPod\tTEXT\tpane_id\t1.7\t-\tPane::currentCommand\tThe pane id."

        val thrown = assertThrows(IllegalArgumentException::class.java) { parseFieldCatalog(bad) }
        assertTrue(thrown.message!!.contains("unknown owner"), thrown.message)
    }

    @Test
    fun `rejects a duplicate field name on the same owner`() {
        val bad = "$validRow\n$validRow"

        val thrown = assertThrows(IllegalArgumentException::class.java) { parseFieldCatalog(bad) }
        assertTrue(thrown.message!!.contains("duplicate field"), thrown.message)
    }

    @Test
    fun `rejects a bad kind`() {
        val bad = "id\tPane\tBOOLEAN\tpane_id\t1.7\t-\tPane::currentCommand\tThe pane id."

        val thrown = assertThrows(IllegalArgumentException::class.java) { parseFieldCatalog(bad) }
        assertTrue(thrown.message!!.contains("bad kind"), thrown.message)
    }

    @Test
    fun `rejects a relation whose kind is not the placeholder`() {
        val bad = "panes\tWindow\tTEXT\t-\t-\tto-many:Pane\tWindow::panes\tThis link's panes."

        val thrown = assertThrows(IllegalArgumentException::class.java) { parseFieldCatalog(bad) }
        assertTrue(thrown.message!!.contains("must declare kind '-'"), thrown.message)
    }

    @Test
    fun `rejects a malformed relation`() {
        val bad = "panes\tWindow\t-\t-\t-\tmany:Pane\tWindow::panes\tThis link's panes."

        val thrown = assertThrows(IllegalArgumentException::class.java) { parseFieldCatalog(bad) }
        assertTrue(thrown.message!!.contains("bad relation"), thrown.message)
    }

    @Test
    fun `rejects a relation naming an unknown target`() {
        val bad = "panes\tWindow\t-\t-\t-\tto-many:Pod\tWindow::panes\tThis link's panes."

        val thrown = assertThrows(IllegalArgumentException::class.java) { parseFieldCatalog(bad) }
        assertTrue(thrown.message!!.contains("unknown relation target"), thrown.message)
    }
}
