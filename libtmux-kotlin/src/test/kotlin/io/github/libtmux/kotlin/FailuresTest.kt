package io.github.libtmux.kotlin

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
import io.github.libtmux.transport.DispatchOutcome
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** The core's sealed failures read as sealed in Kotlin: a `when` over them needs no `else`. */
class FailuresTest {

    private fun nextStep(failure: LibTmuxException): String =
        when (failure) {
            is TargetGoneException -> "look it up again"
            is ServerUnavailableException -> "start a server"
            is CommandRejectedException -> "change the request"
            is DispatchException -> if (failure.safeToRetry()) "send it again" else "read state first"
            is ControlEndedException -> "attach again"
            is UnsupportedFeatureException -> "do without"
            is UnencodableTextException -> "use a UTF-8 locale"
            is MalformedResponseException -> "report it"
            is CardinalityException.NoMatch -> "nothing matched"
            is CardinalityException.MultipleMatches -> "${failure.atLeast()} matched"
        }

    @Test
    fun `a when over every failure compiles without an else`() {
        assertEquals("3 matched", nextStep(CardinalityException.MultipleMatches("many", 3)))
        assertEquals(
            "send it again",
            nextStep(DispatchException.TimedOut("late", DispatchOutcome.NOT_DISPATCHED, null)),
        )
        assertEquals("read state first", nextStep(DispatchException.Failed("broke", DispatchOutcome.UNKNOWN, null)))
    }
}
