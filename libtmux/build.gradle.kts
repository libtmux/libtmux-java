import java.lang.module.ModuleFinder

plugins {
    id("libtmux.published-library")
    id("libtmux.api-diff")
}

apiDiff {
    // The module descriptor, not Java accessibility, is what actually hides this package (see the
    // module-descriptor check below); a caller can never reach it either way.
    excludePackages.add("io.github.libtmux.internal")
}

dependencies {
    compileOnly(libs.errorprone.annotations)
    // Kotlin reads a Java collection marked @ReadOnly as a read-only List, Set, or Map, with or
    // without this jar on the caller's path; nothing reads it at runtime.
    compileOnly(libs.kotlin.annotations.jvm)
    testImplementation(libs.asm)
    testImplementation(libs.reactive.streams.tck.flow)
    testRuntimeOnly(libs.testng.engine)
}

// The core resolves nothing at runtime. Anything that would change that belongs in another module.
// No Automatic-Module-Name: module-info.java names this module, and the manifest attribute is
// ignored once a descriptor is present — two spellings of one name, one of which cannot be checked.

// The module descriptor is the only thing that actually hides io.github.libtmux.internal: its three
// classes are public because several packages here share them, and on a classpath that makes them
// everyone's. Read back off the built jar rather than asserted in a test, because tests run on the
// classpath — in the unnamed module, where the descriptor is not there to check.
tasks.jar {
    val built = archiveFile
    doLast {
        val descriptor = ModuleFinder.of(built.get().asFile.toPath())
            .findAll()
            .firstOrNull()
            ?.descriptor()
            ?: error("the published jar carries no module descriptor")
        require(descriptor.name() == "io.github.libtmux") {
            "the published module is named ${descriptor.name()}"
        }
        val exported = descriptor.exports().map { it.source() }.toSet()
        require("io.github.libtmux.internal" !in exported) {
            "the published module exports io.github.libtmux.internal"
        }
        val packages = descriptor.packages().filterNot { it == "io.github.libtmux.internal" }
        val unexported = packages - exported
        require(unexported.isEmpty()) {
            "the published module hides packages a caller needs: $unexported"
        }
    }
}

// Every other lint stays on. -exports fires only because the annotations above are required
// statically and not transitively, which is the point: a consumer never sees them at runtime, and
// making them transitive to silence this made every modular consumer fail to compile.
tasks.compileJava {
    options.compilerArgs.add("-Xlint:-exports")
    // kotlin-annotations-jvm names no module, so it sits on the classpath, which a named module
    // cannot read unless told to. It is needed only here: its annotation is kept in the class file
    // for the Kotlin compiler and never loaded.
    options.compilerArgs.addAll(listOf("--add-reads", "io.github.libtmux=ALL-UNNAMED"))
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("-add-reads", "io.github.libtmux=ALL-UNNAMED")
}

// A consumer with a module descriptor of its own, compiled against the built jar and nothing else.
// The descriptor check above reads what the jar declares; this reads what a consumer can do with it,
// which is a different question and the one that was wrong: the jar declared exactly what it meant
// to and consumers still could not compile.
val moduleConsumer =
    tasks.register<JavaCompile>("moduleConsumerCompile") {
        group = "verification"
        description = "Compiles a modular consumer against the published jar with nothing else on its module path."
        val jar = tasks.jar.flatMap { it.archiveFile }
        source = fileTree("src/moduleConsumer/java")
        destinationDirectory = layout.buildDirectory.dir("module-consumer")
        classpath = files()
        javaCompiler = javaToolchains.compilerFor(java.toolchain)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        options.compilerArgumentProviders.add(
            CommandLineArgumentProvider {
                listOf("--module-path", jar.get().asFile.absolutePath)
            }
        )
        inputs.file(jar)
    }

tasks.check { dependsOn(moduleConsumer) }

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("carrier") } }

// The carrier gate needs a scheduler with exactly one carrier. That is a JVM-wide setting, so it
// gets its own fork rather than distorting every other test in the suite.
val carrierTest =
    tasks.register<Test>("carrierTest") {
        group = "verification"
        description = "Runs the transport's drain gate under a one-carrier virtual-thread scheduler."
        val tests = sourceSets.test.get()
        testClassesDirs = tests.output.classesDirs
        classpath = tests.runtimeClasspath
        useJUnitPlatform { includeTags("carrier") }
        systemProperty("jdk.virtualThreadScheduler.parallelism", "1")
        systemProperty("jdk.virtualThreadScheduler.maxPoolSize", "1")

        // A filter change elsewhere can reduce this fork to nothing, and an empty run reports
        // success. The gate has to prove it ran at all before its green means anything.
        val results = reports.junitXml.outputLocation
        doLast {
            val written = results.get().asFile.listFiles { file -> file.name.endsWith(".xml") }
            require(!written.isNullOrEmpty()) { "the carrier gate discovered no tests, so its result is vacuous" }
        }
    }

tasks.check { dependsOn(carrierTest) }

// ------------------------------------------------------------------------------------- SBOM

// The claim in README.md ("No runtime dependencies") is falsifiable, so the SBOM the build already
// produces (see libtmux.sbom.gradle.kts) is read back rather than trusted: a component
// listed here is a runtime dependency the README does not know about.
val checkSbomHasNoRuntimeDependencies =
    tasks.register("checkSbomHasNoRuntimeDependencies") {
        group = "verification"
        description = "Fails when the core's SBOM lists a runtime dependency, or was not produced."
        val bom = tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom").flatMap { it.jsonOutput }
        dependsOn(tasks.named("cyclonedxDirectBom"))
        inputs.file(bom)

        doLast {
            val file = bom.get().asFile
            require(file.isFile) { "the SBOM was not produced at ${file.absolutePath}" }

            val document = (groovy.json.JsonSlurper().parse(file) as Map<*, *>)
            val metadata = document["metadata"] as Map<*, *>?
            val componentName = (metadata?.get("component") as Map<*, *>?)?.get("name")
            require(componentName == "libtmux") { "the SBOM's own component is named $componentName, not libtmux" }

            val components = document["components"] as List<*>? ?: emptyList<Any>()
            require(components.isEmpty()) {
                "the core's SBOM lists ${components.size} runtime dependency(ies), " +
                    "but the core resolves nothing at runtime: $components"
            }
            logger.lifecycle("the SBOM names libtmux and lists no runtime dependencies")
        }
    }

tasks.check { dependsOn(checkSbomHasNoRuntimeDependencies) }
