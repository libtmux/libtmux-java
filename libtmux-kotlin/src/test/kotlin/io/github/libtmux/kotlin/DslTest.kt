package io.github.libtmux.kotlin

import io.github.libtmux.junit5.TmuxExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.github.libtmux.Server as JavaServer

@ExtendWith(TmuxExtension::class)
class DslTest {

    @Test
    fun `one session, two windows, one split — not three windows`(javaServer: JavaServer) = runBlocking {
        val server = Server.open(javaServer.config())

        val session = server.newSession {
            name = "dsl-shape"
            window { name = "editor" }
            window {
                name = "shell"
                split { toRight(); percent(30) }
            }
        }

        assertEquals(2, session.windows.size, "two window { } blocks must make two windows, not three")
        assertEquals("editor", session.windows[0].name)
        assertEquals("shell", session.windows[1].name)
        assertEquals(2, session.windows[1].panes.size, "the split adds one pane to the second window")
        assertEquals(1, session.windows[0].panes.size, "the first window has no split")
    }

    @Test
    fun `directory is independently settable at every level`(javaServer: JavaServer) = runBlocking {
        val server = Server.open(javaServer.config())
        val root = java.nio.file.Files.createTempDirectory("libtmux-kotlin-dsl-test-").toRealPath()
        val nested = java.nio.file.Files.createDirectory(root.resolve("nested")).toRealPath()

        val session = server.newSession {
            name = "dsl-directory"
            directory = root
            window {
                name = "w"
                split { directory = nested }
            }
        }

        val window = session.windows[0]
        val outerPane = window.panes[0]
        val splitPane = window.panes[1]
        // Exact-path equality risks a symlink-resolution mismatch between this JVM's view and
        // tmux's own report (macOS /tmp, for one); the leaf name is enough to prove independence.
        assertEquals("nested", splitPane.currentPath.fileName.toString())
        assertTrue(outerPane.currentPath != splitPane.currentPath)
    }

    /**
     * `@DslMarker` blocks an inner block from reaching an outer one's receiver by accident:
     * `SessionBuilder.window` is not visible, unqualified, from inside a nested `split { }`, because
     * both builders carry the same `@LibTmuxDsl` marker. Without `@DslMarker` this would compile —
     * Kotlin's implicit-receiver search would just find `window` on the enclosing `SessionBuilder`.
     */
    @Test
    fun `a nested split cannot reach the enclosing session's window builder unqualified`() {
        val fixture = """
            import io.github.libtmux.kotlin.Server
            import io.github.libtmux.kotlin.newSession

            fun use(server: Server) {
                suspend fun body() {
                    server.newSession {
                        window {
                            split {
                                window { }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val result = KotlincHarness.compile(fixture)

        assertTrue(!result.succeeded, "expected reaching the outer window { } unqualified to fail:\n${result.diagnostics}")
        // @DslMarker does not hide the name (that would be "unresolved reference"); it blocks implicit
        // -receiver resolution from crossing into the outer scope, which the compiler reports as this
        // specific diagnostic instead.
        assertTrue(
            result.diagnostics.contains(
                "cannot be called in this context with an implicit receiver",
                ignoreCase = true,
            ),
            "expected an implicit-receiver diagnostic naming window, got:\n${result.diagnostics}",
        )
    }

    @Test
    fun `the same nested block reaches the outer builder when qualified`() {
        val fixture = """
            import io.github.libtmux.kotlin.Server
            import io.github.libtmux.kotlin.newSession

            fun use(server: Server) {
                suspend fun body() {
                    server.newSession {
                        window {
                            split {
                                this@newSession.window { }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val result = KotlincHarness.compile(fixture)

        assertTrue(result.succeeded, "expected the qualified form to compile:\n${result.diagnostics}")
    }
}
