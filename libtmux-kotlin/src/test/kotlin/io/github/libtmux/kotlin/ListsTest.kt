package io.github.libtmux.kotlin

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ListsTest {

    @Test
    fun `a java list is mutable and the view is not`() {
        val captured = java.util.List.of("pane")

        assertTrue(captured is MutableList<*>)
        val view = captured.readOnly()
        assertEquals(listOf("pane"), view)
        assertFalse(view is MutableList<*>)
    }
}
