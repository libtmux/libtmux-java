package io.github.libtmux.kotlin

import io.github.libtmux.control.ControlClient
import io.github.libtmux.control.Delivery
import io.github.libtmux.control.PaneOutput
import io.github.libtmux.exception.ControlEndedException
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A stress probe for [coldFlowFrom], the cold `flow {}` bridge over
 * [io.github.libtmux.control.EventSubscription.poll]/`onReady`.
 *
 * Every event this fake control client emits, under a bursty producer racing a slow collector, must
 * be accounted for: as a [Delivery.Event], or folded into a [Delivery.Gap]'s count, with none
 * unaccounted for.
 */
@Tag("slow")
class FlowBridgeStressTest {

    private companion object {
        const val EVENT_COUNT = 300_000
        const val BUFFER_CAPACITY = 64
    }

    @Test
    fun `every produced event is delivered or gapped, none silently lost`(@TempDir directory: Path) {
        val client = FakeControlClients.bursty(directory, EVENT_COUNT)
        try {
            val subscription = client.subscribeOutput(BUFFER_CAPACITY)
            // The synchronizing command: the fixture's script only starts blasting once it has read
            // this request off the wire, so the subscription above is always registered first.
            val sender = thread(start = true) { runCatching { client.send("display-message") } }

            var delivered = 0L
            var gapped = 0L
            var polls = 0
            runBlocking {
                withTimeout(60.seconds) {
                    try {
                        coldFlowFrom<PaneOutput>({ subscription }).collect { step ->
                            polls++
                            when (step) {
                                is Delivery.Event -> delivered++
                                is Delivery.Gap -> gapped += step.missed
                            }
                            // Slow the collector down periodically, without paying for it on every
                            // item, so a bursty producer periodically outruns a stalled collector.
                            if (polls % 1000 == 0) {
                                kotlinx.coroutines.delay(5)
                            }
                        }
                    } catch (_: ControlEndedException) {
                        // The fixture closes its stdout once it is done producing; a routine end.
                    }
                }
            }
            sender.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(10))

            assertEquals(EVENT_COUNT.toLong(), delivered + gapped, "delivered=$delivered gapped=$gapped")
            assertTrue(gapped > 0, "expected the small buffer to overflow at least once under this producer")
        } finally {
            client.close()
        }
    }
}
