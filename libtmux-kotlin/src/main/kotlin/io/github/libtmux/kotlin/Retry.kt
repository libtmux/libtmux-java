package io.github.libtmux.kotlin

import io.github.libtmux.exception.DispatchException

/**
 * Retries [block] up to [times] more times after a [DispatchException] whose
 * [DispatchException.safeToRetry] says the exact same request can be sent again.
 *
 * No retry executor lives in this library: [DispatchException.safeToRetry] is computed by the
 * operation that threw it from its own catalogued idempotence, so this needs no per-call lookup and
 * composes with any retry library a caller already uses instead of replacing it.
 */
public suspend fun <T> retryIfSafe(times: Int = 1, block: suspend () -> T): T {
    repeat(times) {
        try {
            return block()
        } catch (failure: DispatchException) {
            if (!failure.safeToRetry()) throw failure
        }
    }
    return block()
}
