// Runs a module's real-tmux tests against every released tmux this project supports, rather than
// against whichever build happens to be on PATH. Compatibility that is never executed is a claim,
// not a fact.
//
// The matrix itself is a local tree of built tmuxes — one directory per lane, each with bin/tmux —
// supplied through the `libtmuxMatrix` property. Nothing about its location is committed.

// Declared so the source-set accessors resolve; the module already has it via the library plugin.
plugins { java }

val lanes = listOf("3.2a", "3.3a", "3.4", "3.5", "3.6", "3.7", "3.7a", "3.7b")

val matrix = providers.gradleProperty("libtmuxMatrix")

val laneTasks =
    lanes.map { lane ->
        tasks.register<Test>("test-tmux-$lane") {
            group = "verification"
            description = "Runs the real-tmux tests against tmux $lane."
            val tests = sourceSets.test.get()
            testClassesDirs = tests.output.classesDirs
            classpath = tests.runtimeClasspath
            useJUnitPlatform { excludeTags("fixture", "carrier", "benchmark") }

            // Resolved lazily: the task is registered whether or not a matrix exists, so asking for
            // it without one fails loudly instead of the lane silently not existing.
            val binary = matrix.map { "$it/$lane/bin/tmux" }
            systemProperty("libtmux.tmux", binary.getOrElse("tmux-matrix-not-configured"))
            // Declared so the suite can check it got the tmux this lane is named after. Without it a
            // lane that ignored the binary would run against whatever is on PATH and still be green.
            systemProperty("libtmux.tmux.expected", lane)
            onlyIf {
                require(matrix.isPresent) {
                    "no tmux matrix configured; set -PlibtmuxMatrix=<dir> to a tree of tmux builds"
                }
                require(File(binary.get()).canExecute()) { "tmux $lane is missing from the matrix" }
                true
            }
        }
    }

// The benchmark lives in its own module now, so nothing here has to exclude it and no module has
// to remember a tag to stay fast.
