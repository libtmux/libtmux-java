// Measures what each carrier costs, and rewrites docs/benchmarks/modes.md from a real run.
//
// Its own module, and never published. A benchmark takes seconds per case and starts a server per
// case, so it must not run in any ordinary suite; keeping it in a published artifact's tests made
// that a matter of remembering a tag rather than a matter of where the code lives.
plugins { id("libtmux.java-library") }

dependencies {
    testImplementation(project(":libtmux"))
    testImplementation(project(":libtmux-junit5"))
}

// Nothing here belongs to `check`: it writes a file and takes seconds. Run it when the table needs
// regenerating.
tasks.named<Test>("test") { enabled = false }

tasks.register<Test>("modeBenchmark") {
    group = "verification"
    description = "Measures each execution mode and rewrites docs/benchmarks/modes.md."
    val tests = sourceSets.test.get()
    testClassesDirs = tests.output.classesDirs
    classpath = tests.runtimeClasspath
    useJUnitPlatform { includeTags("benchmark") }
    systemProperty("libtmux.tmux", providers.gradleProperty("libtmuxTmux").getOrElse("tmux"))
    systemProperty(
        "libtmux.benchmark.out",
        rootProject.layout.projectDirectory.file("docs/benchmarks/modes.md").asFile.path,
    )
    outputs.upToDateWhen { false }
}
