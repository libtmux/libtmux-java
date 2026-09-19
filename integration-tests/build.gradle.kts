// The library's real-tmux suite. Not published, and deliberately not inside any module that is:
// these tests exercise every artifact together, and a suite living in one artifact's test source
// set makes that artifact's dependencies and lifecycle answerable for how the whole library is
// tested.
plugins {
    id("libtmux.java-library")
    id("libtmux.tmux-matrix")
}

dependencies {
    testImplementation(project(":libtmux"))
    testImplementation(project(":libtmux-jackson"))
    testImplementation(project(":libtmux-junit5"))
}

// The locale lane needs a JVM whose platform encoding is not UTF-8, which is a process-wide choice
// read before main runs. So it gets its own fork rather than a flag, and the ordinary run excludes
// it: under UTF-8 every assertion in it would pass for the wrong reason.
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("locale") } }

val localeTest =
    tasks.register<Test>("localeTest") {
        group = "verification"
        description = "Runs the real-tmux suite's locale lane under a JVM that cannot encode non-ASCII arguments."
        val tests = sourceSets.test.get()
        testClassesDirs = tests.output.classesDirs
        classpath = tests.runtimeClasspath
        useJUnitPlatform { includeTags("locale") }

        // LC_ALL outranks LANG and LC_CTYPE for both the JVM's platform encoding and tmux's own
        // guess at whether its client reads UTF-8, so one variable puts both halves in the state
        // this lane is about.
        environment("LC_ALL", "C")

        // The macOS JDK encodes arguments and file names as UTF-8 whatever the locale says, so no
        // variable can put this lane's JVM in the state it tests there.
        onlyIf("the JVM on macOS always encodes with UTF-8") {
            !System.getProperty("os.name").startsWith("Mac")
        }

        // A tag that stopped matching would reduce this fork to nothing, and an empty run reports
        // success. The lane has to prove it ran at all before its green means anything.
        val results = reports.junitXml.outputLocation
        doLast {
            val written = results.get().asFile.listFiles { file -> file.name.endsWith(".xml") }
            require(!written.isNullOrEmpty()) { "the locale lane discovered no tests, so its result is vacuous" }
        }
    }

tasks.check { dependsOn(localeTest) }
