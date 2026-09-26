package io.github.libtmux.kotlin

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ConsumerBaselineTest {

    /** A Kotlin 2.1 consumer reads metadata up to 2.2; anything newer locks it out. */
    @Test
    fun `the published metadata stays readable by Kotlin 2 1`() {
        for (type in listOf(
            "io.github.libtmux.kotlin.Server",
            "io.github.libtmux.kotlin.Session",
            "io.github.libtmux.kotlin.Window",
            "io.github.libtmux.kotlin.Pane",
            "io.github.libtmux.kotlin.Client",
            "io.github.libtmux.kotlin.ControlClient",
        )) {
            val version = Class.forName(type).getAnnotation(Metadata::class.java).metadataVersion.toList()
            assertTrue(version[0] < 2 || (version[0] == 2 && version[1] <= 2), "$type has metadata $version")
        }
    }
}
