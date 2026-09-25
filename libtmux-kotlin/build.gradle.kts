// Kotlin ergonomics over the Java API. Sugar, not enablement: the core is already null-safe from
// Kotlin because it is annotated with JSpecify, which Kotlin has read since 1.5.20.
//
// Nothing written in Java may depend on this module. Per the JSpecify specification a class carrying
// `@kotlin.Metadata` is not null-marked — the Kotlin compiler does not emit full nullness into
// binaries yet (KT-47417) — so a Kotlin-authored API is strictly worse for a Java consumer and for
// NullAway than a Java-authored one. The root build fails if that dependency ever appears.
plugins {
    id("libtmux.publication")
    id("libtmux.sbom")
    id("libtmux.tmux-matrix")
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
    // A consumer's compiler reads metadata at most one minor version newer than itself. 2.2 is
    // what kotlinx-coroutines 1.11 is compiled for, so a consumer on Kotlin 2.1 can use both;
    // left unpinned, this module's metadata would follow the build's own compiler.
    coreLibrariesVersion = "2.2.0"
    // Every public declaration states its visibility and its return type. A library's ABI should not
    // be something the compiler inferred.
    explicitApi()
    // The dump under api/ is the published binary surface. check fails when it changes.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        // The whole point of this module is that the Java API's nullness is real. Strict mode turns
        // a mismatch against a @NullMarked type into an error here, so this module compiling is
        // itself evidence that the annotations downstairs are correct.
        freeCompilerArgs.addAll("-Xjspecify-annotations=strict")
    }
}

dependencies {
    api(project(":libtmux"))
    // Flow is part of the public signature. The core stays free of this.
    api(libs.kotlinx.coroutines.core)

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

// ------------------------------------------------------------------------- oldest consumer

// Kotlin 2.1 is the oldest compiler this module says can read it. The metadata test checks the
// number the build wrote; this compiles a consumer with that compiler, against the jar this module
// publishes, so the claim is what a 2.1 project actually meets.
val oldestKotlin: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies { oldestKotlin("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.21") }

val compileOldestConsumer =
    tasks.register<JavaExec>("compileOldestConsumer") {
        description = "Compiles a consumer of this module with Kotlin 2.1, the oldest it claims."
        group = "verification"
        val consumer = layout.projectDirectory.file("src/consumer/Consumer.kt")
        val published = files(tasks.named("jar"), configurations.named("runtimeClasspath"))
        val output = layout.buildDirectory.dir("oldest-consumer")
        inputs.file(consumer).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.files(published).withNormalizer(ClasspathNormalizer::class.java)
        outputs.dir(output)
        classpath = oldestKotlin
        mainClass = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    consumer.asFile.path,
                    "-d",
                    output.get().asFile.path,
                    "-classpath",
                    published.asPath,
                    "-no-stdlib",
                    "-no-reflect",
                    // Kotlin 2.1's compiler rejects -jvm-target above 23; the flag governs bytecode
                    // this compile emits, not its ability to read this module's higher-targeted jar.
                    "-jvm-target",
                    "23",
                    "-Werror",
                )
            },
        )
    }

tasks.named("check") { dependsOn(compileOldestConsumer) }

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

        // The same set Documentation.readable finds on the Java side, so a document is either
        // checked in both languages or neither. A file tree rather than a computed list, so a
        // document appearing later changes this task's inputs instead of being invisible to it.
        val documentationRoot = rootProject.projectDir
        val documents =
            rootProject.fileTree(documentationRoot) {
                include("README.md", "*/README.md", "docs/guide/*.md")
                exclude("**/build/**")
            }
        val generated = layout.buildDirectory.dir("generated/documentation")
        // How many Kotlin fences each document holds. A fence added or lost is a line changed here,
        // not a count that still clears a floor.
        val inventory = layout.projectDirectory.file("documentation-snippets.txt")

        inputs.files(documents).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(inventory).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(generated)

        doLast {
            val fence =
                Regex(
                    """(?:<!--\s*snippet:\s*([^>]*?)\s*-->\s*\n)?^```kotlin\n(.*?)^```""",
                    setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
                )
            val counts = sortedMapOf<String, Int>()
            generated.get().asFile.deleteRecursively()
            var found = 0

            // Sorted, so the generated file does not depend on the order of a directory scan.
            documents.sorted().forEach { document ->
                val text = document.readText()
                val where = document.relativeTo(documentationRoot).path
                fence.findAll(text).forEach { match ->
                    val directive = match.groupValues[1]
                    if (directive.startsWith("skip:")) return@forEach
                    found++
                    counts.merge(where, 1, Int::plus)

                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    // A backticked name may hold neither a dot nor a separator. The whole path
                    // rather than the file name: every module has a README, and two with a fence on
                    // the same line would otherwise generate one function twice.
                    val name = "${where.replace('/', ' ').replace('.', ' ')} line $line"

                    // A shown result becomes an assertion, the same rule docs-tests applies to the
                    // Java fences: what a reader sees after the arrow is what toString produced, so
                    // documentation cannot claim a value the library does not give.
                    val shown = Regex("""^(\s*)(.+?)\s*//\s*(?:\u2192|->)\s*(.*?)\s*$""")

                    // Kotlin wants imports at the top of a file, so a snippet's own are hoisted into
                    // its own file. Each snippet has one, in a package of its own: an import one
                    // snippet needs is not lent to another, and nothing of io.github.libtmux.kotlin
                    // is in scope unless the snippet imports it, as a reader's code would have to.
                    val imports = sortedSetOf<String>()
                    val body =
                        match.groupValues[2]
                            .lines()
                            .filter { statement ->
                                if (statement.trimStart().startsWith("import ")) {
                                    imports += statement.trim()
                                    false
                                } else {
                                    true
                                }
                            }
                            .map { statement ->
                                val result = shown.matchEntire(statement)
                                if (result == null || result.groupValues[2].trimStart().startsWith("//")) {
                                    statement
                                } else {
                                    val expected = result.groupValues[3].replace("\\", "\\\\").replace("\"", "\\\"")
                                    """${result.groupValues[1]}assertEquals("$expected", (${result.groupValues[2]}).toString())"""
                                }
                            }
                    // A snippet gets `server`, as the Java fences do. Anything else it reads
                    // without building has to be named on a `// Given:` line, so a reader sees
                    // what the snippet assumes rather than finding a name declared nowhere.
                    val given =
                        body
                            .firstOrNull { it.isNotBlank() }
                            ?.let { Regex("""^\s*//\s*Given:\s*(.*)$""").matchEntire(it) }
                            ?.groupValues
                            ?.get(1)
                            ?.split(',')
                            ?.map { it.substringBefore(':').trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty()
                    val fixtures =
                        linkedMapOf(
                            "config" to "server.config()",
                            "session" to "server.sessions()[0]",
                            "window" to "server.sessions()[0].windows()[0]",
                            "pane" to "server.sessions()[0].windows()[0].panes()[0]",
                            "socket" to "socketPath.path()",
                        )
                    val unknown = given - fixtures.keys
                    require(unknown.isEmpty()) { "$where line $line: Given names $unknown; a snippet may assume ${fixtures.keys}" }
                    val declared = given.joinToString("") { "        val $it = ${fixtures.getValue(it)}\n" }
                    val file = generated.get().asFile.resolve("io/github/libtmux/docs/kotlin/s$found/Snippet.kt")
                    file.parentFile.mkdirs()
                    file.writeText(
                        buildString {
                            appendLine("// Generated from $where line $line. Edit the Markdown, not this file.")
                            appendLine("@file:Suppress(\"unused\", \"UNUSED_VARIABLE\", \"NAME_SHADOWING\", \"RedundantSuppression\")")
                            appendLine()
                            appendLine("package io.github.libtmux.docs.kotlin.s$found")
                            appendLine()
                            imports.forEach { appendLine(it) }
                            // What every Java fence may use too: the core, and the harness.
                            appendLine("import io.github.libtmux.*")
                            appendLine("import io.github.libtmux.junit5.TmuxExtension")
                            appendLine("import io.github.libtmux.junit5.TmuxSocketPath")
                            appendLine("import kotlin.test.assertEquals")
                            appendLine("import org.junit.jupiter.api.Test")
                            appendLine("import org.junit.jupiter.api.extension.ExtendWith")
                            appendLine()
                            append(
                                """
                                |@ExtendWith(TmuxExtension::class)
                                |class Snippet {
                                |    @Test
                                |    fun `$name`(server: Server, socketPath: TmuxSocketPath) {
                                |$declared
                                |        // run is inline, so a snippet's own declarations shadow the ones
                                |        // above rather than colliding with them, and a bare return still
                                |        // leaves the test.
                                |        run {
                                |${body.joinToString("\n") { "            $it" }}
                                |        }
                                |    }
                                |}
                                |
                                """.trimMargin(),
                            )
                        },
                    )
                }
            }

            val expected =
                inventory.asFile.readLines()
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() }
                    .associate { entry -> entry.substringAfter(' ').trim() to entry.substringBefore(' ').toInt() }
                    .toSortedMap()
            require(counts == expected) {
                "Kotlin fences per document are $counts, and documentation-snippets.txt says $expected; " +
                    "update it when a fence is added or removed on purpose"
            }
            logger.lifecycle("generated $found Kotlin documentation snippets")
        }
    }

sourceSets.test { kotlin.srcDir(documentedKotlin) }
