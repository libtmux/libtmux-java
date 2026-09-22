package io.github.libtmux.kotlin

import io.github.libtmux.ServerConfig
import io.github.libtmux.SessionId
import io.github.libtmux.control.ControlClient
import io.github.libtmux.control.ControlEndedException
import io.github.libtmux.control.Delivery
import io.github.libtmux.control.PaneOutput
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeliveriesTest {

    @Test
    fun `kept fails a gap`() {
        assertFailsWith<IllegalStateException> { Delivery.Gap<String>(3).kept() }
        assertEquals("x", Delivery.Event("x").kept())
    }

    @Test
    fun `a flow emits the gap ahead of the event that survived`(@TempDir directory: Path) {
        val client = client(directory, floodThenWait())
        try {
            val subscription = client.subscribeOutput(1)
            client.send("display-message")

            val found = mutableListOf<Delivery<PaneOutput>>()
            val both = CompletableDeferred<Unit>()
            runBlocking {
                val reading =
                    launch {
                        subscription.deliveries().collect { step ->
                            found += step
                            if (found.size == 2) {
                                both.complete(Unit)
                            }
                        }
                    }
                try {
                    withTimeout(2.seconds) { both.await() }
                } catch (_: TimeoutCancellationException) {
                    reading.cancel()
                }
                reading.cancelAndJoin()
            }

            val gap = assertIs<Delivery.Gap<PaneOutput>>(found[0])
            assertEquals(4L, gap.missed)
            val event = assertIs<Delivery.Event<PaneOutput>>(found[1])
            assertEquals("five", event.value.data)
            assertTrue(subscription.isClosed())
        } finally {
            client.close()
        }
    }

    @Test
    fun `a timed read returns the gap and stays open`(@TempDir directory: Path) {
        val client = client(directory, floodThenWait())
        try {
            val subscription = client.subscribeOutput(1)
            client.send("display-message")

            val gap = runBlocking { subscription.awaitDelivery(0.milliseconds) }

            assertEquals(4L, assertIs<Delivery.Gap<PaneOutput>>(assertNotNull(gap)).missed)
            assertFalse(subscription.isClosed())
        } finally {
            client.close()
        }
    }

    @Test
    fun `a timed read fails when the client ended the subscription`(@TempDir directory: Path) {
        val client = client(directory, exitAfterAttach())
        try {
            val subscription = client.subscribeOutput(1)
            assertFailsWith<ControlEndedException> {
                runBlocking { subscription.awaitDelivery(2.seconds) }
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `cancelling a collection closes the subscription`(@TempDir directory: Path) {
        val client = client(directory, waitForever())
        try {
            val subscription = client.subscribeOutput(1)
            val started = CompletableDeferred<Unit>()
            runBlocking {
                val job =
                    launch {
                        subscription.deliveries().onStart { started.complete(Unit) }.collect {}
                    }
                withTimeout(5.seconds) { started.await() }
                job.cancelAndJoin()
            }
            assertTrue(subscription.isClosed())
        } finally {
            client.close()
        }
    }

    @Test
    fun `a negative wait is rejected`(@TempDir directory: Path) {
        val client = client(directory, waitForever())
        try {
            val subscription = client.subscribeOutput(1)
            assertFailsWith<IllegalArgumentException> {
                runBlocking { subscription.awaitDelivery((-1).milliseconds) }
            }
            assertFalse(subscription.isClosed())
        } finally {
            client.close()
        }
    }

    private fun client(directory: Path, body: String): ControlClient {
        val fake = directory.resolve("tmux")
        Files.writeString(fake, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"))
        val config = ServerConfig.builder().binary(fake.toString()).build()
        return ControlClient.attach(config, SessionId("\$0"))
    }

    /** Five outputs, then the reply, so a capacity of one has already overflowed. */
    private fun floodThenWait(): String =
        """
        printf '%%begin 100 1 0\n%%end 100 1 0\n'
        IFS= read -r request
        printf '%%begin 101 1 0\n%%end 101 1 0\n'
        IFS= read -r request
        printf '%%output %%1 one\n'
        printf '%%output %%1 two\n'
        printf '%%output %%1 three\n'
        printf '%%output %%1 four\n'
        printf '%%output %%1 five\n'
        printf '%%begin 102 1 0\n%%end 102 1 0\n'
        sleep 30
        """
            .trimIndent()

    private fun exitAfterAttach(): String =
        """
        printf '%%begin 100 1 0\n%%end 100 1 0\n'
        IFS= read -r request
        printf '%%begin 101 1 0\n%%end 101 1 0\n'
        sleep 0.4
        """
            .trimIndent()

    private fun waitForever(): String =
        """
        printf '%%begin 100 1 0\n%%end 100 1 0\n'
        IFS= read -r request
        printf '%%begin 101 1 0\n%%end 101 1 0\n'
        sleep 30
        """
            .trimIndent()
}
