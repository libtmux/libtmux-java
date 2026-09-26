package io.github.libtmux.kotlin

import io.github.libtmux.control.Delivery
import io.github.libtmux.control.PaneOutput
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A cold flow opens its subscription only when collection starts, so output a command causes before
 * then is lost. `onSubscribed` runs in between. The fake client writes its output before it
 * answers, so a send that waits for its answer has already put every line in the buffer: all of it
 * arrives only if the send ran after the subscription opened.
 */
class FlowSubscribedTest {

    @Test
    fun `output a command causes in onSubscribed is all delivered`(@TempDir directory: Path) {
        val client = FakeControlClients.bursty(directory, 5)
        try {
            val received = runBlocking {
                withTimeout(10.seconds) {
                    coldFlowFrom<PaneOutput>({ client.subscribeOutput(16) }) {
                        runInterruptible { client.send("display-message") }
                    }.take(5).toList()
                }
            }

            assertEquals(List(5) { "e$it" }, received.map { Delivery.kept(it).data.trim() })
        } finally {
            client.close()
        }
    }
}
