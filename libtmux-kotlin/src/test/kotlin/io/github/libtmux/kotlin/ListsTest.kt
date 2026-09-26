package io.github.libtmux.kotlin

import io.github.libtmux.transport.CommandResult
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ListsTest {

    @Test
    fun `a list the core returns is read-only in Kotlin`() {
        val result = CommandResult(0, listOf("out"), listOf())

        // Resolved at compile time: a list Kotlin read as mutable picks the overload that refuses.
        assertEquals(listOf("out"), readOnly(result.stdout()))
    }

    private fun <T> readOnly(list: List<T>): List<T> = list

    @Deprecated("a core list reached Kotlin as mutable", level = DeprecationLevel.ERROR)
    @JvmName("refuseMutableList")
    private fun <T> readOnly(list: MutableList<T>): List<T> = list
}
