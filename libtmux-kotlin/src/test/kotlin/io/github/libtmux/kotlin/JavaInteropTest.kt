package io.github.libtmux.kotlin

import io.github.libtmux.junit5.TmuxExtension
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.github.libtmux.Server as JavaServer

/**
 * A mixed Java and Kotlin codebase hands handles across the boundary: a server opened in Java, such
 * as this test fixture's, becomes a wrapper as it is, and each wrapper answers the Java handle it
 * holds, the same object rather than a copy.
 */
@ExtendWith(TmuxExtension::class)
class JavaInteropTest {

    @Test
    fun `a Java server is wrapped as it is and every handle answers its Java one`(javaServer: JavaServer) = runBlocking {
        val server = Server.fromJava(javaServer)
        assertSame(javaServer, server.asJava)

        val session = server.newSession("interop")
        val window = session.windows.first()
        val pane = window.panes.first()

        assertEquals(session.id, session.asJava.id())
        assertEquals(window.id, window.asJava.id())
        assertEquals(pane.id, pane.asJava.id())
        assertSame(javaServer, session.asJava.server())
    }
}
