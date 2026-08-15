// Kotlin ergonomics over the Java API. Sugar, not enablement: the core is already null-safe from
// Kotlin because it is annotated with JSpecify, which Kotlin has read since 1.5.20.
//
// Nothing written in Java may depend on this module. Per the JSpecify specification a class carrying
// `@kotlin.Metadata` is not null-marked — the Kotlin compiler does not emit full nullness into
// binaries yet (KT-47417) — so a Kotlin-authored API is strictly worse for a Java consumer and for
// NullAway than a Java-authored one. The root build fails if that dependency ever appears.
plugins {
    id("libtmux.publication")
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    // Every public declaration states its visibility and its return type. A library's ABI should not
    // be something the compiler inferred.
    explicitApi()
    compilerOptions {
        // The whole point of this module is that the Java API's nullness is real. Strict mode turns
        // a mismatch against a @NullMarked type into an error here, so this module compiling is
        // itself evidence that the annotations downstairs are correct.
        freeCompilerArgs.addAll("-Xjspecify-annotations=strict")
    }
}

dependencies {
    api(project(":libtmux"))

    // For the tests that execute this module's README, against a real tmux server.
    testImplementation(project(":libtmux-junit5"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// Reproducible archives, as every other module here publishes them.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("755") }
    filePermissions { unix("644") }
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "io.github.libtmux.kotlin",
            "Implementation-Version" to provider { project.version.toString() },
        )
    }
}

// ---------------------------------------------------------------------- documentation snippets

// Every Kotlin fence in the documentation, turned into a test.
//
// docs-tests reads the Java fences and cannot read these: doing it the same way would mean running
// the Kotlin compiler in-process. Generating a source file instead lets the ordinary Kotlin
// compilation and the ordinary test run do the checking, which is the same guarantee by a shorter
// road — and the generated file is the README, so the two cannot drift.
val documentedKotlin =
    tasks.register("generateDocumentationSnippets") {
        description = "Turns every Kotlin fence in the documentation into a test."

        val documents =
            listOf(
                rootProject.file("README.md"),
                rootProject.file("libtmux-kotlin/README.md"),
                rootProject.file("docs/guide/kotlin.md"),
            )
        val generated = layout.buildDirectory.dir("generated/documentation")

        inputs.files(documents).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(generated)

        doLast {
            val fence =
                Regex(
                    """(?:<!--\s*snippet:\s*([^>]*?)\s*-->\s*\n)?^```kotlin\n(.*?)^```""",
                    setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
                )
            val imports = sortedSetOf<String>()
            val cases = StringBuilder()
            var found = 0

            documents.forEach { document ->
                val text = document.readText()
                fence.findAll(text).forEach { match ->
                    val directive = match.groupValues[1]
                    if (directive.startsWith("skip:")) return@forEach
                    found++

                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    // A backticked name may not hold a dot, and the point of the name is to say
                    // which document and which line, so only the dot is spelt differently.
                    val name = "${document.name.replace('.', ' ')} line $line"

                    // Kotlin wants imports at the top of a file, so a snippet's own are hoisted.
                    val body =
                        match.groupValues[2].lines().filter { statement ->
                            if (statement.trimStart().startsWith("import ")) {
                                imports += statement.trim()
                                false
                            } else {
                                true
                            }
                        }
                    cases.append(
                        """
                        |    @Test
                        |    fun `$name`(server: Server, socketPath: TmuxSocketPath) {
                        |        val config = server.config()
                        |        val session = server.sessions()[0]
                        |        val window = session.windows()[0]
                        |        val pane = window.panes()[0]
                        |        val socket = socketPath.path()
                        |
                        |        // run is inline, so a snippet's own declarations shadow the ones
                        |        // above rather than colliding with them, and a bare return still
                        |        // leaves the test.
                        |        run {
                        |${body.joinToString("\n") { "            $it" }}
                        |        }
                        |    }
                        |
                        """.trimMargin(),
                    )
                }
            }

            require(found >= 5) { "only found $found Kotlin snippets; the extractor has stopped working" }

            val file = generated.get().asFile.resolve("io/github/libtmux/kotlin/DocumentationSnippetsTest.kt")
            file.parentFile.mkdirs()
            file.writeText(
                buildString {
                    appendLine("// Generated from the documentation. Edit the Markdown, not this file.")
                    appendLine("@file:Suppress(\"unused\", \"UNUSED_VARIABLE\", \"NAME_SHADOWING\", \"RedundantSuppression\")")
                    appendLine()
                    appendLine("package io.github.libtmux.kotlin")
                    appendLine()
                    imports.forEach { appendLine(it) }
                    appendLine("import io.github.libtmux.*")
                    appendLine("import io.github.libtmux.junit5.TmuxExtension")
                    appendLine("import io.github.libtmux.junit5.TmuxSocketPath")
                    appendLine("import org.junit.jupiter.api.Test")
                    appendLine("import org.junit.jupiter.api.extension.ExtendWith")
                    appendLine()
                    appendLine("@ExtendWith(TmuxExtension::class)")
                    appendLine("class DocumentationSnippetsTest {")
                    append(cases)
                    appendLine("}")
                },
            )
            logger.lifecycle("generated $found Kotlin documentation snippets")
        }
    }

sourceSets.test { kotlin.srcDir(documentedKotlin) }
