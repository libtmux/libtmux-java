package io.github.libtmux.kotlin

import io.github.libtmux.Pane_
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * That the sugar means exactly what the Java it wraps means.
 *
 * This module compiling at all is the other half of the point: it is built with
 * `-Xjspecify-annotations=strict`, so a nullness mismatch against the core's `@NullMarked` types is
 * an error here rather than a warning. Kotlin seeing the core as null-safe is not a claim in a
 * README; it is this source set.
 */
class FiltersTest {

    @Test
    fun `not inverts the expression it is given`() {
        val active = Pane_.active().isTrue()

        assertEquals(active.negate().describe(), (!active).describe())
    }

    @Test
    fun `double negation is not silently collapsed`() {
        val active = Pane_.active().isTrue()

        assertEquals("not not ${active.describe()}", (!!active).describe())
    }
}
