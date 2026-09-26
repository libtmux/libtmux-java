package io.github.libtmux.kotlin

import io.github.libtmux.ServerConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

/**
 * [ExecutionPolicy.commands] is a [kotlinx.coroutines.CoroutineDispatcher], which does not expose
 * its own width for direct inspection, so this measures it the only way that proves anything: run
 * more coroutines than the configured bound at once, with each holding the dispatcher just long
 * enough to be counted, and check the *observed peak concurrency* rather than trust the number back.
 */
class ExecutionPolicyTest {

    @Test
    fun `commands is bounded by the server's admission bound, not by Dispatchers-IO's own width`() = runBlocking {
        val bound = 3
        val config = ServerConfig.builder().maxConcurrentCommands(bound).build()
        val policy = ExecutionPolicy.default(config)

        val concurrent = AtomicInteger()
        val peak = AtomicInteger()
        val started = bound * 4
        (1..started)
            .map {
                async {
                    withContext(policy.commands) {
                        val now = concurrent.incrementAndGet()
                        peak.updateAndGet { previous -> maxOf(previous, now) }
                        // A blocking hold, not a suspending delay(): delay() gives its slot back to a
                        // limitedParallelism dispatcher the instant it parks, so every one of the
                        // `started` coroutines gets to increment before any of them decrements — a
                        // measured peak of `started`, proving nothing about the bound. commands exists
                        // to bound real blocking calls (ControlClient's runInterruptible among them),
                        // so the probe has to occupy a thread the same way those calls do.
                        Thread.sleep(100)
                        concurrent.decrementAndGet()
                    }
                }
            }
            .awaitAll()

        assertTrue(peak.get() <= bound, "peak concurrency ${peak.get()} exceeded the admission bound $bound")
        assertEquals(bound, peak.get(), "the pool never actually reached its own bound")
    }

    @Test
    fun `streamReads is sized independently of commands`() = runBlocking {
        val config = ServerConfig.builder().maxConcurrentCommands(1).build()
        val policy = ExecutionPolicy.default(config, streamReadCapacity = 5)

        val peak = AtomicInteger()
        val concurrent = AtomicInteger()
        (1..5)
            .map {
                async {
                    withContext(policy.streamReads) {
                        val now = concurrent.incrementAndGet()
                        peak.updateAndGet { previous -> maxOf(previous, now) }
                        delay(100.milliseconds)
                        concurrent.decrementAndGet()
                    }
                }
            }
            .awaitAll()

        // commands is bound to 1, but streamReads is its own pool: five concurrent stream reads run
        // side by side even though the command pool that same config would build could not.
        assertEquals(5, peak.get())
    }
}
