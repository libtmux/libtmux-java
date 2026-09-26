package io.github.libtmux.codegen.scala

import io.github.libtmux.codegen.catalog.readOperationCatalog
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScalaOperationGeneratorTest {

    private val catalog = readOperationCatalog(fixture("operation-catalog-scala.json"))
    private val paneOps = ScalaOperationGenerator.byOwner(catalog).toMap().getValue("io.github.libtmux.Pane")
    private val direct = ScalaOperationGenerator.directStyle("io.github.libtmux.Pane", paneOps)
    private val cats = ScalaOperationGenerator.catsForwards("io.github.libtmux.Pane", paneOps)

    private fun fixture(name: String): File =
        File(requireNotNull(javaClass.classLoader.getResource(name)) { "missing fixture $name" }.toURI())

    @Test
    fun `a mutation forwards to the Java member`() {
        assertTrue(direct.contains("def sendLine(command: String): Unit =\n      self.asJava.sendLine(command)"), direct)
    }

    @Test
    fun `a trailing varargs parameter stays varargs and is spread`() {
        assertTrue(direct.contains("def respawn(command: String*): Unit =\n      self.asJava.respawn(command*)"), direct)
    }

    @Test
    fun `a captured operation forwards purely on both facades`() {
        val info = "def info: io.github.libtmux.snapshot.PaneState ="
        assertTrue(direct.contains(info), direct)
        assertTrue(cats.contains(info), cats)
    }

    @Test
    fun `a mutation is F-wrapped on the Cats facade only`() {
        assertTrue(direct.contains("def kill(): Unit ="), direct)
        assertTrue(cats.contains("def kill(): F[Unit] =\n      self.server.execution(self.underlying.asJava.kill())"), cats)
    }

    @Test
    fun `Cats skips the forwards it writes by hand`() {
        val server = ScalaOperationGenerator.combinedCatsForwards(catalog)
        assertFalse(Regex("""def (batch|chain|channel)\(""").containsMatchIn(server), server)
    }

    @Test
    fun `an unknown operation kind fails instead of being skipped`() {
        val corrupted = catalog.copy(operations = catalog.operations.mapIndexed { i, op -> if (i == 0) op.copy(kind = "BOGUS") else op })
        val failure = assertThrows<IllegalArgumentException> { ScalaOperationGenerator.byOwner(corrupted) }
        assertTrue(failure.message!!.contains("BOGUS"))
    }

    @Test
    fun `a type spelling parses its arguments at every depth`() {
        assertEquals(
            JavaTypeSpelling.Named(
                "java.util.Map",
                listOf(
                    JavaTypeSpelling.Named("java.lang.String"),
                    JavaTypeSpelling.Named("java.util.List", listOf(JavaTypeSpelling.ArrayOf(JavaTypeSpelling.Primitive("int")))),
                ),
            ),
            JavaTypeSpelling.parse("java.util.Map<java.lang.String, java.util.List<int[]>>"),
        )
    }
}
