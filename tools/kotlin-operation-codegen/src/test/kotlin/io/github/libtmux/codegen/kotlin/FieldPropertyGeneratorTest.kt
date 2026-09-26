package io.github.libtmux.codegen.kotlin

import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FieldPropertyGeneratorTest {

    private fun fixture(name: String): File =
        File(checkNotNull(javaClass.classLoader.getResource(name)) { "missing test resource $name" }.toURI())

    private val rows = readFieldCatalog(fixture("field-catalog-fixture.tsv"))
    private val sources = generateFieldPropertyFiles(rows).associate { it.name to it.toString() }

    @Test
    fun `one file is produced per owner`() {
        assertTrue(sources.keys == setOf("PaneFields", "WindowFields"))
    }

    // This file's own package (io.github.libtmux.kotlin.query) is neither the Java handle types'
    // package (io.github.libtmux) nor the wrapper types' (io.github.libtmux.kotlin), so KotlinPoet
    // resolves the same-simple-name collision with distinct import aliases rather than qualifying
    // either side — confirmed here rather than assumed.

    @Test
    fun `a scalar field property delegates to the Java metamodel's static accessor`() {
        val pane = sources.getValue("PaneFields")
        assertTrue(pane.contains("import io.github.libtmux.Pane as LibtmuxPane"), pane)
        assertTrue(pane.contains("public val KotlinPane.Companion.command: TextField<LibtmuxPane>"), pane)
        assertTrue(pane.contains("get() = TextField(Pane_.command())"), pane)
    }

    @Test
    fun `a to-many relation becomes a ToManyField parameterized by both types`() {
        val window = sources.getValue("WindowFields")
        assertTrue(window.contains("ToManyField<LibtmuxWindow, Pane>"), window)
        assertTrue(window.contains("get() = ToManyField(Window_.panes())"), window)
    }

    @Test
    fun `a to-one relation becomes a ToOneField parameterized by both types`() {
        val window = sources.getValue("WindowFields")
        assertTrue(window.contains("ToOneField<LibtmuxWindow, Session>"), window)
        assertTrue(window.contains("get() = ToOneField(Window_.session())"), window)
    }
}
