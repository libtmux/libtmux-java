package io.github.libtmux.examples.api.kotlin

import io.github.libtmux.WindowSpec
import io.github.libtmux.SplitSpec
import io.github.libtmux.kotlin.*
import io.github.libtmux.kotlin.query.name
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/** Create a window and read the updated session. */
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
                val session = server.session(Session.name eq "work-one")
                val window = session.newWindow(
                    WindowSpec.builder().named("api-window").running("/bin/cat").build()
                )
                println("created=${window.name}")
                val refreshed = server.session(Session.name eq "work-one")
                println("windows=" + refreshed.windows.map { it.name }.sorted().joinToString(","))
            }
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        System.err.println("Example failed: ${error.message}")
        exitProcess(1)
    }
}
