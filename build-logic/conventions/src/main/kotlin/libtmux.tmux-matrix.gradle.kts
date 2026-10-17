// Runs a module's real-tmux tests against every released tmux this project supports, rather than
// against whichever build happens to be on PATH. Compatibility that is never executed is a claim,
// not a fact.
//
// The matrix itself is a local tree of built tmuxes — one directory per lane, each with bin/tmux —
// supplied through the `libtmuxMatrix` property. Nothing about its location is committed.

// Declared so the source-set accessors resolve; the module already has it via the library plugin.
plugins { java }

val lanes = listOf("3.3a", "3.4", "3.5", "3.6", "3.7", "3.7a", "3.7b", "3.7c")

// Ahead of the supported range: previews of what tmux ships next, run so a break shows up before the
// release that makes it real rather than after. Allowed to fail, and left out of testTmuxMatrix and
// the README's claimed range on purpose — 3.8-rc2 is a candidate for a release not yet cut, and master
// is upstream's development branch, not a version at all.
val previewLanes = listOf("3.8", "3.8-rc2", "3.8-rc3", "master")

// Below the floor: these run only the version check cases against the real binary, which must refuse
// every call and leave a sentinel session, option and buffer as it was. They are part of the matrix
// because a refusal that a fake alone vouches for is not one. Each is also a daemon reached by a
// client of the first supported release: a client as old as 3.0a or 3.1c only ever rejects `-N`.
val rejectionLanes = listOf("3.0a", "3.1c", "3.2a", "3.3")
val clientLane = "3.3a"

val matrix = providers.gradleProperty("libtmuxMatrix")

fun registerLane(lane: String, rejection: Boolean = false) =
    tasks.register<Test>("test-tmux-$lane") {
        group = "verification"
        description = "Runs the real-tmux tests against tmux $lane."
        val tests = sourceSets.test.get()
        testClassesDirs = tests.output.classesDirs
        classpath = tests.runtimeClasspath
        // "locale" joins the excludes for the same reason "carrier" is here: it needs a JVM
        // started differently — one whose platform encoding is not UTF-8 — so running it in an
        // ordinary fork asserts nothing and fails saying so.
        useJUnitPlatform { excludeTags("fixture", "carrier", "benchmark", "locale") }

        // By class, not by tag: the Scala suites run under another engine that ignores tags.
        if (rejection) {
            // A module names the classes its rejection lanes run in `libtmuxRejectionTests`. One that
            // names none has nothing to refuse with, so its lane is skipped. One that does fails the
            // lane unless every one of them produced a result, because Gradle itself fails only when
            // no filter matches at all, and a lane that ran a test less proves less.
            val classes = (project.findProperty("libtmuxRejectionTests") as? List<*>).orEmpty().map { it.toString() }
            classes.forEach { filter.includeTestsMatching(it) }
            onlyIf("the module names no rejection test class") { classes.isNotEmpty() }
            doLast {
                val results = reports.junitXml.outputLocation.get().asFile
                val missing = classes.filter { !File(results, "TEST-$it.xml").exists() }
                check(missing.isEmpty()) { "the rejection lane ran no test of $missing" }
            }
        }

        // Resolved lazily: the task is registered whether or not a matrix exists, so asking for
        // it without one fails loudly instead of the lane silently not existing.
        val binary = matrix.map { "$it/$lane/bin/tmux" }
        systemProperty("libtmux.tmux", binary.getOrElse("tmux-matrix-not-configured"))
        // Declared so the suite can check it got the tmux this lane is named after. Without it a
        // lane that ignored the binary would run against whatever is on PATH and still be green.
        systemProperty("libtmux.tmux.expected", lane)
        // Whether this lane's binary is below the floor, so the version check suite can require the
        // outcome the lane was built for instead of accepting whichever branch the binary takes.
        systemProperty("libtmux.tmux.rejection", rejection.toString())
        if (rejection) {
            // The binary above is the daemon here; the library's own client is a newer build.
            systemProperty("libtmux.tmux.client", matrix.map { "$it/$clientLane/bin/tmux" }.getOrElse("tmux-matrix-not-configured"))
            systemProperty("libtmux.tmux.client.expected", clientLane)
        }
        val configured = matrix.isPresent
        val configuredBinary = binary.getOrElse("tmux-matrix-not-configured")
        // A bare `tmux` is this lane's build too. Code that names only a socket, as a reader's
        // does, takes the binary from PATH, and a client of another release talking to this lane's
        // server makes the server exit.
        val path = providers.environmentVariable("PATH")
        doFirst {
            environment("PATH", File(binary.get()).parent + File.pathSeparator + path.getOrElse(""))
        }
        onlyIf {
            require(configured) {
                "no tmux matrix configured; set -PlibtmuxMatrix=<dir> to a tree of tmux builds"
            }
            require(File(configuredBinary).canExecute()) { "tmux $lane is missing from the matrix" }
            true
        }
    }

val laneTasks = lanes.map { registerLane(it) }
previewLanes.forEach { registerLane(it) }
val rejectionTasks = rejectionLanes.map { registerLane(it, rejection = true) }

rootProject.tasks.maybeCreate("testTmuxMatrix").apply {
    group = "verification"
    description = "Runs every real-tmux test against every supported tmux release."
    dependsOn(laneTasks, rejectionTasks)
}
