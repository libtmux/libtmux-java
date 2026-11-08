package io.github.libtmux.kotlin

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import io.github.libtmux.ServerEndpoint
import io.github.libtmux.ServerLaunch

class PrivateServerTest {

    private fun launch(): ServerLaunch {
        val root = Path.of("/tmp/libtmux-java-test")
        Files.createDirectories(root)
        return ServerLaunch.builder()
            .binary(System.getProperty("libtmux.tmux", "tmux"))
            .root(Files.createTempDirectory(root, "k-"))
            .build()
    }

    @Test
    fun `a scoped private server answers inside the block and is gone after it`() = runBlocking {
        var directory: Path? = null
        val name = withPrivateServer(launch()) { server ->
            val socket = (server.config.endpoint() as ServerEndpoint.SocketPath).path()
            directory = socket.parent
            assertTrue(server.isAlive())
            server.newSession("scoped").name
        }
        assertEquals("scoped", name)
        assertFalse(Files.exists(directory!!), "the block's server left its directory behind")
    }

    @Test
    fun `a private server fails the scope that leaks it nothing and survives a throwing block`() = runBlocking {
        var directory: Path? = null
        val failure = runCatching {
            withPrivateServer(launch()) { server ->
                directory = (server.config.endpoint() as ServerEndpoint.SocketPath).path().parent
                error("deliberate")
            }
        }.exceptionOrNull()
        assertEquals("deliberate", failure?.message)
        assertFalse(Files.exists(directory!!), "a throwing block left its server directory behind")
    }
}
