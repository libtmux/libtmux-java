package io.github.libtmux.examples.api.kotlin

import io.github.libtmux.SplitSpec
import io.github.libtmux.kotlin.*
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/** List the sessions returned by a server read. */
fun main() {
    try {
        runBlocking {
            withPrivateServer { server ->
                val workOne = server.newSession {
                    name = "work-one"
                    window { name = "editor"; running("/bin/cat") }
                    window { name = "logs"; running("/bin/cat") }
                }
                workOne.windows.first().split(SplitSpec.builder().toRight().running("/bin/cat").build())
                server.newSession {
                    name = "work-two"
                    window { name = "editor"; running("/bin/cat") }
                }
                println("sessions=" + server.sessions().map { it.name }.sorted().joinToString(","))
            }
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        System.err.println("Example failed: ${error.message}")
        exitProcess(1)
    }
}
