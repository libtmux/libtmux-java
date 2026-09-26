package io.github.libtmux.kotlin

import io.github.libtmux.exception.CardinalityException
import io.github.libtmux.junit5.TmuxExtension
import io.github.libtmux.kotlin.query.active
import io.github.libtmux.kotlin.query.command
import io.github.libtmux.kotlin.query.name
import io.github.libtmux.kotlin.query.panes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.github.libtmux.Server as JavaServer

@ExtendWith(TmuxExtension::class)
class QueryFieldsTest {

    @Test
    fun `a scalar field filters through the companion`(javaServer: JavaServer) = runBlocking {
        val server = Server.open(javaServer.config())
        val session = server.newSession("field-scalar")
        val running = session.activeWindow!!.activePane!!.currentCommand

        val matches = server.panes(Pane.command eq running)
        assertEquals(true, matches.isNotEmpty())
    }

    @Test
    fun `a to-many relation composes with a scalar predicate on the related type`(javaServer: JavaServer) =
        runBlocking {
            val server = Server.open(javaServer.config())
            server.newSession("field-relation")

            // Every window has at least one active pane, so "any window has an active pane" always
            // matches at least the window this session just made.
            val matches = server.windows(Window.panes any Pane.active.isTrue())
            assertEquals(true, matches.isNotEmpty())
        }

    @Test
    fun `session throws CardinalityException NoMatch on zero, sessionOrNull returns null`(javaServer: JavaServer) =
        runBlocking {
            val server = Server.open(javaServer.config())

            assertNull(server.sessionOrNull(Session.name eq "no-such-session-field-test"))
            assertFailsWith<CardinalityException.NoMatch> {
                server.session(Session.name eq "no-such-session-field-test")
            }
        }

    @Test
    fun `session throws CardinalityException MultipleMatches on more than one`(javaServer: JavaServer) = runBlocking {
        val server = Server.open(javaServer.config())
        server.newSession("field-dup-a")
        server.newSession("field-dup-b")

        val failure = assertFailsWith<CardinalityException.MultipleMatches> {
            server.session(Session.name startsWith "field-dup-")
        }
        assertEquals(true, failure.atLeast() >= 2)

        val failureOrNull = assertFailsWith<CardinalityException.MultipleMatches> {
            server.sessionOrNull(Session.name startsWith "field-dup-")
        }
        assertEquals(true, failureOrNull.atLeast() >= 2)
    }
}
