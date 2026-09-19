import java.lang.module.ModuleFinder

plugins { id("libtmux.published-library") }

dependencies { compileOnly(libs.errorprone.annotations) }

// The core resolves nothing at runtime. Anything that would change that belongs in another module.
tasks.jar { manifest { attributes("Automatic-Module-Name" to "io.github.libtmux") } }

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
