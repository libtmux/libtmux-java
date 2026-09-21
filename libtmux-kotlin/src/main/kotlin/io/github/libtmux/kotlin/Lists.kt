package io.github.libtmux.kotlin

/**
 * A Kotlin [List] over a Java list.
 *
 * Java's list type is mutable in Kotlin, including a capture that refuses
 * `add`. This view does not offer it.
 */
public fun <T> MutableList<T>.readOnly(): List<T> =
    object : AbstractList<T>() {
        override val size: Int
            get() = this@readOnly.size

        override fun get(index: Int): T = this@readOnly[index]
    }
