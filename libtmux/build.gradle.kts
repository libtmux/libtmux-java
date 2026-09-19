import java.lang.module.ModuleFinder

plugins { id("libtmux.published-library") }

dependencies { compileOnly(libs.errorprone.annotations) }

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
tasks.compileJava { options.compilerArgs.add("-Xlint:-exports") }

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
