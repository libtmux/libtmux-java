// Fails when a module with Test tasks lacks the tmux quarantine or the JVM-side refusal.
// No plugins block, so the root build applies this file by path.
tasks.register("everyTestModuleIsQuarantined") {
    group = "verification"
    description = "Fails when a module with test tasks lacks the tmux quarantine or the refusal."

    val offenders = provider {
        subprojects
            .filter { it.tasks.withType(Test::class.java).isNotEmpty() }
            .filter { candidate ->
                val refusal = candidate.configurations.findByName("testRuntimeOnly")?.dependencies?.any {
                    it.group == "io.github.libtmux.build" && it.name == "test-sandbox"
                } ?: false
                !candidate.pluginManager.hasPlugin("libtmux.tmux-quarantine") || !refusal
            }
            .map { it.path }
    }

    doLast {
        val found = offenders.get()
        require(found.isEmpty()) {
            "these run tests outside the tmux quarantine: ${found.joinToString(", ")}"
        }
        logger.lifecycle("every module with tests runs them under the tmux quarantine")
    }
}
