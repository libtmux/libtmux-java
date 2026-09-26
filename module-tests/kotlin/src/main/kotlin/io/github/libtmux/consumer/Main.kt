package io.github.libtmux.consumer

import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerEndpoint
import io.github.libtmux.kotlin.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/** Drives a real tmux through the staged libtmux-kotlin, resolved through the staged BOM. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
fun main() = runBlocking {
    val root = Files.createDirectories(Path.of("/tmp/libtmux-java-test"))
    val directory = Files.createTempDirectory(root, "consumer-kotlin-")
    val config = ServerConfig.builder()
        .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
        .build()
    try {
        val output = withServer(config) { server ->
            val pane = server.newSession("staged-consumer").activeWindow?.activePane ?: error("no active pane")
            val ran = pane.run("printf 'staged\\n'", timeout = 30.seconds)
            server.killServer()
            ran.output()
        }
        check(output == listOf("staged")) { "the command did not come back as it went in: $output" }
    } finally {
        directory.deleteRecursively()
    }
    println("staged kotlin consumer ran through libtmux-kotlin")
}
