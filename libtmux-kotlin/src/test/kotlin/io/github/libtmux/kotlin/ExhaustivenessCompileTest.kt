package io.github.libtmux.kotlin

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the exhaustive `when` in `FailuresTest` and the README is doing real enforcement work, not
 * merely happening to compile: a `when` over the same sealed tree, missing one branch and with no
 * `else`, is compiled fresh here and must fail. Reusing the real
 * `io.github.libtmux.exception.LibTmuxException` tree (not a stand-in) means a leaf added to the real
 * hierarchy is exactly what this test demonstrates breaking every such `when` at.
 */
class ExhaustivenessCompileTest {

    private val exhaustiveWhen = """
        import io.github.libtmux.exception.CardinalityException
        import io.github.libtmux.exception.CommandRejectedException
        import io.github.libtmux.exception.ControlEndedException
        import io.github.libtmux.exception.DispatchException
        import io.github.libtmux.exception.LibTmuxException
        import io.github.libtmux.exception.MalformedResponseException
        import io.github.libtmux.exception.ServerUnavailableException
        import io.github.libtmux.exception.TargetGoneException
        import io.github.libtmux.exception.UnencodableTextException
        import io.github.libtmux.exception.UnsupportedFeatureException

        fun nextStep(failure: LibTmuxException): String =
            when (failure) {
                is TargetGoneException -> "a"
                is ServerUnavailableException -> "b"
                is CommandRejectedException -> "c"
                is DispatchException -> "d"
                is ControlEndedException -> "e"
                is UnsupportedFeatureException -> "f"
                is UnencodableTextException -> "g"
                is MalformedResponseException -> "h"
                is CardinalityException.NoMatch -> "i"
                is CardinalityException.MultipleMatches -> "j"
            }
    """.trimIndent()

    @Test
    fun `every leaf present, no else, compiles`() {
        val result = KotlincHarness.compile(exhaustiveWhen)
        assertTrue(result.succeeded, "expected the full when to compile:\n${result.diagnostics}")
    }

    @Test
    fun `one leaf missing, no else, fails to compile`() {
        val missingOneLeaf = exhaustiveWhen.replace(
            "is CardinalityException.MultipleMatches -> \"j\"\n",
            "",
        )
        assertTrue(
            missingOneLeaf != exhaustiveWhen,
            "the fixture string replace found nothing; this test would pass for the wrong reason",
        )

        val result = KotlincHarness.compile(missingOneLeaf)

        assertTrue(!result.succeeded, "expected a when missing a leaf, with no else, to fail:\n${result.diagnostics}")
        assertTrue(
            result.diagnostics.contains("exhaustive", ignoreCase = true),
            "expected an exhaustiveness diagnostic, got:\n${result.diagnostics}",
        )
    }
}
