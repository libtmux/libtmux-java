package io.github.libtmux.kotlin

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/** What one [KotlincHarness.compile] attempt produced. */
internal data class CompileResult(val exitCode: ExitCode, val diagnostics: String) {
    val succeeded: Boolean get() = exitCode == ExitCode.OK
}

/**
 * Compiles a Kotlin source fixture with the real, embedded 2.4.x compiler this module is compiled
 * with, against this module's own real compiled classes — so a compile-test proves the compiler
 * enforces what it claims (exhaustiveness, `@DslMarker` scoping) against the real sealed tree and the
 * real builders, not a hand-rolled stand-in that could quietly drift from them.
 */
internal object KotlincHarness {

    fun compile(source: String): CompileResult {
        val classpath = requireNotNull(System.getProperty("libtmux.kotlin.testClasspath")) {
            "libtmux.kotlin.testClasspath system property is not set; run through Gradle's test task"
        }
        val workDir = Files.createTempDirectory("libtmux-kotlin-compile-harness")
        val sourceFile = workDir.resolve("Fixture.kt")
        Files.writeString(sourceFile, source)
        val outDir = workDir.resolve("out")
        Files.createDirectories(outDir)

        val diagnostics = ByteArrayOutputStream()
        val exitCode = K2JVMCompiler().exec(
            PrintStream(diagnostics, true, "UTF-8"),
            "-classpath",
            classpath,
            "-d",
            outDir.toString(),
            "-jvm-target",
            "25",
            // This process has no separate "Kotlin home" the embeddable compiler can find its own
            // stdlib/reflect jars under; the explicit classpath above already carries them from this
            // module's own runtime classpath, which -no-stdlib/-no-reflect is what stops it guessing.
            "-no-stdlib",
            "-no-reflect",
            sourceFile.toString(),
        )
        return CompileResult(exitCode, diagnostics.toString("UTF-8"))
    }
}
