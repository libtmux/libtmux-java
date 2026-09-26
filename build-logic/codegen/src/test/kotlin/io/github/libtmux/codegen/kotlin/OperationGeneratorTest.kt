package io.github.libtmux.codegen.kotlin

import io.github.libtmux.codegen.catalog.readOperationCatalog
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class OperationGeneratorTest {

    private val catalog = readOperationCatalog(fixture("operation-catalog-kotlin.json"))
    private val files = generateFiles(catalog)
    private val sources = files.associate { it.name to it.toString() }

    private fun fixture(name: String): File =
        File(checkNotNull(javaClass.classLoader.getResource(name)) { "missing test resource $name" }.toURI())

    @Test
    fun `one file is produced per owner with a generatable operation`() {
        assertEquals(
            setOf("PaneOperations", "ServerOperations", "SessionOperations", "OptionsOperations", "EnvironmentOperations"),
            sources.keys,
        )
    }

    @Test
    fun `a MUTATION returning a wrapped handle wraps the Java result`() {
        val server = sources.getValue("ServerOperations")
        assertTrue(
            server.contains("Session(java.newSession(name), this)"),
            "expected the new session to be wrapped through the Server receiver:\n$server",
        )
    }

    @Test
    fun `an Optional of a wrapped handle maps to a nullable wrapper via map-then-orElse`() {
        val server = sources.getValue("ServerOperations")
        assertTrue(
            server.contains("java.session(name).map { Session(it, this) }.orElse(null)"),
            "expected Optional<Session> to become a nullable Session:\n$server",
        )
        assertTrue(server.contains("public suspend fun Server.session(name: String): Session?"))
    }

    @Test
    fun `a List of a wrapped handle maps each element`() {
        val server = sources.getValue("ServerOperations")
        assertTrue(
            server.contains("java.sessions(expression).map { Session(it, this) }"),
            "expected List<Session> elements to each be wrapped:\n$server",
        )
    }

    @Test
    fun `a FilterExpr parameter keeps the Java handle type argument, unwrapped`() {
        val server = sources.getValue("ServerOperations")
        // FilterExpr itself imports cleanly; its type argument collides with the Kotlin Session
        // wrapper this same file returns, so KotlinPoet fully qualifies only that argument.
        assertTrue(
            server.contains("expression: FilterExpr<io.github.libtmux.Session>"),
            "expected FilterExpr's own type argument to stay the Java Session, not the Kotlin wrapper:\n$server",
        )
    }

    @Test
    fun `a wrapped handle parameter is unwrapped through its java property`() {
        val session = sources.getValue("SessionOperations")
        assertTrue(
            session.contains("java.selectWindow(window.java)"),
            "expected the Window parameter to be unwrapped before the Java call:\n$session",
        )
    }

    @Test
    fun `a java-time-Duration parameter is converted at the call site`() {
        val pane = sources.getValue("PaneOperations")
        assertTrue(
            pane.contains("java.clearHistory(olderThan.toJavaDuration())"),
            "expected the Duration parameter to be converted before the Java call:\n$pane",
        )
        assertTrue(pane.contains("olderThan: kotlin.time.Duration") || pane.contains("olderThan: Duration"))
    }

    @Test
    fun `a varargs String array becomes a Kotlin vararg, spread into the Java call`() {
        val server = sources.getValue("ServerOperations")
        assertTrue(server.contains("vararg argv: String"), "expected a Kotlin vararg parameter:\n$server")
        assertTrue(server.contains("java.cmd(*argv)"), "expected the vararg to be spread into the Java call:\n$server")
    }

    @Test
    fun `every generated function suspends and dispatches through the policy`() {
        val server = sources.getValue("ServerOperations")
        assertTrue(server.contains("public suspend fun Server.isAlive(): Boolean"))
        assertTrue(server.contains("runInterruptible(policy.commands)"))
        val session = sources.getValue("SessionOperations")
        assertTrue(session.contains("runInterruptible(server.policy.commands)"))
    }

    @Test
    fun `a CAPTURED operation is not generated, even for a wrapped owner`() {
        val pane = sources.getValue("PaneOperations")
        assertFalse(pane.contains("fun Pane.pid"), "pid is CAPTURED and must stay handwritten:\n$pane")
    }

    @Test
    fun `a LIFECYCLE operation is not generated, even for a wrapped owner`() {
        assertFalse(sources.getValue("ServerOperations").contains("fun Server.control"))
    }

    @Test
    fun `an operation on an unrecognized owner is skipped rather than crashing`() {
        assertFalse(sources.values.any { it.contains("fun NotWrapped.get") })
    }

    @Test
    fun `a newly wrapped subsystem owner generates its READ operation as a suspend extension`() {
        val options = sources.getValue("OptionsOperations")
        assertTrue(
            options.contains("public suspend fun Options.effective(): Map<String, String>"),
            "expected Options.effective() to generate now that Options is wrapped:\n$options",
        )
        assertTrue(options.contains("runInterruptible(server.policy.commands)"), options)
    }

    @Test
    fun `a generic method is skipped, not generated with a broken type`() {
        assertFalse(sources.getValue("OptionsOperations").contains("fun Options.set"), "OptionKey<T> has a type parameter")
    }

    @Test
    fun `a handwritten-override overload is skipped even though it is otherwise generatable`() {
        assertFalse(
            sources.getValue("OptionsOperations").contains("fun Options.`get`"),
            "Options#get(String) sits beside the handwritten OptionKey<T> overload and must stay handwritten too",
        )
    }

    @Test
    fun `a Set of a primitive maps to a Kotlin Set, unconverted`() {
        val environment = sources.getValue("EnvironmentOperations")
        assertTrue(
            environment.contains("public suspend fun Environment.removed(): Set<String>"),
            "expected java.util.Set<String> to become a Kotlin Set<String>:\n$environment",
        )
    }

    @Test
    fun `Javadoc code and link tags become KDoc code spans and links`() {
        val session = sources.getValue("SessionOperations")
        assertTrue(session.contains("`Window#select`"), "expected {@code} to become a code span:\n$session")
        assertTrue(session.contains("[Window.select]"), "expected {@link} to become a KDoc link:\n$session")
    }
}
