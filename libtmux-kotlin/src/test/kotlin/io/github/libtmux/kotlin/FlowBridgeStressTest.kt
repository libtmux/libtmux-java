package io.github.libtmux.kotlin

import io.github.libtmux.ServerConfig
import io.github.libtmux.SessionId
import io.github.libtmux.control.ControlClient
import io.github.libtmux.control.Delivery
import io.github.libtmux.control.PaneOutput
import io.github.libtmux.exception.ControlEndedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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
 * A design revision built the same bridge over `callbackFlow`, draining `poll()` in the readiness
 * callback and pushing into the flow's channel. Run against this exact scenario — a bursty producer
 * outrunning a slow collector — that version lost 240,892 of 300,000 events with zero recorded as a
 * [Delivery.Gap]: `trySend` failing against a full channel silently discarded the polled item. This
 * test is the reproduction: every event this fake control client emits is accounted for, as an
 * [Delivery.Event] or folded into a [Delivery.Gap]'s count, with none unaccounted for.
 */
@Tag("slow")
class FlowBridgeStressTest {

    private companion object {
        const val EVENT_COUNT = 300_000
        const val BUFFER_CAPACITY = 64
    }

    @Test
    fun `every produced event is delivered or gapped, none silently lost`(@TempDir directory: Path) {
        val client = bursty(directory, EVENT_COUNT)
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
                        coldFlowFrom<PaneOutput> { subscription }.collect { step ->
                            polls++
                            when (step) {
                                is Delivery.Event -> delivered++
                                is Delivery.Gap -> gapped += step.missed
                            }
                            // Slow the collector down periodically, without paying for it on every
                            // item: a bursty producer racing an occasionally-stalled collector is
                            // exactly the scenario the callbackFlow version lost events under.
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

    /** A fake control client whose process blasts [count] `%output` lines once it is signalled to. */
    private fun bursty(directory: Path, count: Int): ControlClient {
        val fake = directory.resolve("tmux")
        Files.writeString(
            fake,
            "#!/bin/sh\n$PRELUDE" +
                """
                read_request
                answer
                read_request
                i=0
                while [ "${'$'}i" -lt $count ]; do
                    printf '%%output %%1 e%s\n' "${'$'}i"
                    i=${'$'}((i+1))
                done
                answer
                sleep 2
                """.trimIndent() +
                "\n",
        )
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"))
        val config = ServerConfig.builder().binary(fake.toString()).build()
        return ControlClient.attachUnfenced(config, SessionId("\$0"))
    }

    /**
     * What every fixture in this file starts with. tmux follows each request line with a marker
     * line, a `display-message -p` of a token, and a reply ends with the marker's block:
     * `read_request` reads a request and its marker, and `answer` writes a reply block, flagged as
     * this client's command, then the marker's block.
     */
    private val PRELUDE =
        "printf '%%begin 100 1 0\\n%%end 100 1 0\\n'\n" +
            "read_request() { IFS= read -r request && IFS= read -r marker; }\n" +
            "answer() {\n" +
            "  printf '%%begin 1 1 1\\n'\n" +
            "  for line in \"\$@\"; do printf '%s\\n' \"\$line\"; done\n" +
            "  printf '%%end 1 1 1\\n'\n" +
            "  token=\${marker##*\"' '\"}\n" +
            "  printf '%%begin 1 2 1\\n%s\\n%%end 1 2 1\\n' \"\${token%\"'\"}\"\n" +
            "}\n"
}
