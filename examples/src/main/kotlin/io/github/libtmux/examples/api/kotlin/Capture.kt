package io.github.libtmux.examples.api.kotlin

import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerEndpoint
import io.github.libtmux.SessionSpec
import io.github.libtmux.TextOutcome
import io.github.libtmux.kotlin.*
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/** Send input and wait for output before capturing the pane. */
fun main(args: Array<String>) {
    try {
        execute(args)
    } catch (error: Exception) {
        if (error is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        System.err.println("Example failed: ${error.message}")
        exitProcess(1)
    }
}

private fun execute(args: Array<String>) {
    require(args.size == 3) { "expected: tmux-binary socket-path config-file" }
    val config = ServerConfig.builder()
        .binary(args[0])
        .endpoint(ServerEndpoint.socketPath(Path.of(args[1])))
        .configFile(Path.of(args[2]))
        .build()
    runBlocking {
        withServer(config) { server ->
            val session = server.newSession(
                SessionSpec.builder()
                    .named("api-capture")
                    .running("/bin/sh")
                    .build()
            )
            val pane = session.windows.first().panes.first()
            pane.sendKeys(listOf("printf 'api-%s\\n' keys", "Enter"))
            pane.awaitShown("api-keys")
            pane.sendLine("printf 'api-%s\\n' line")
            pane.awaitShown("api-line")
            val lines = pane.capture().map { it.trim() }
            for (want in listOf("api-keys", "api-line")) {
                check(want in lines) { "capture lacks $want" }
                println(want)
            }
        }
    }
}

private suspend fun Pane.awaitShown(text: String) {
    val outcome = awaitText(text, timeout = 5.seconds)
    check(outcome != TextOutcome.TIMED_OUT) { "timed out waiting for $text" }
}
