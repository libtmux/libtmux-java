package io.github.libtmux.kotlin

import io.github.libtmux.exception.DispatchException
import io.github.libtmux.exception.TargetGoneException
import io.github.libtmux.transport.DispatchOutcome
import io.github.libtmux.transport.Idempotence
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class RetryTest {

    @Test
    fun `a safe-to-retry failure is retried until it succeeds`() = runBlocking {
        var attempts = 0
        val result = retryIfSafe(times = 3) {
            attempts++
            if (attempts < 3) {
                throw DispatchException.TimedOut("slow", DispatchOutcome.NOT_DISPATCHED, null)
            }
            "done"
        }

        assertEquals("done", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `an unsafe-to-retry failure is not retried`() = runBlocking {
        var attempts = 0
        assertFailsWith<DispatchException.Failed> {
            retryIfSafe(times = 3) {
                attempts++
                throw DispatchException.Failed("broke", DispatchOutcome.UNKNOWN, Idempotence.NOT_IDEMPOTENT, null)
            }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `a non-DispatchException failure is never caught`() = runBlocking {
        var attempts = 0
        assertFailsWith<TargetGoneException> {
            retryIfSafe(times = 3) {
                attempts++
                throw TargetGoneException("gone")
            }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `times exhausted still runs the block one final time`() = runBlocking {
        var attempts = 0
        assertFailsWith<DispatchException.TimedOut> {
            retryIfSafe(times = 2) {
                attempts++
                throw DispatchException.TimedOut("slow", DispatchOutcome.NOT_DISPATCHED, null)
            }
        }

        // times=2 means: the initial attempt inside the repeat(2), then repeat exhausts and the
        // function makes one final call outside the loop — three calls total.
        assertEquals(3, attempts)
    }
}
