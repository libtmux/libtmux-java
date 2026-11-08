package io.github.libtmux.examples.api.kotlin

import io.github.libtmux.SessionSpec
import io.github.libtmux.kotlin.*
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/** Create a session running cat. */
fun main() {
    try {
        runBlocking {
            withPrivateServer { server ->
                val session = server.newSession(
                    SessionSpec.builder().named("api-session").running("/bin/cat").build()
                )
                println("created=${session.name}")
            }
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        System.err.println("Example failed: ${error.message}")
        exitProcess(1)
    }
}
