// Measures what an operation costs, and rewrites docs/benchmarks/operations.md from a real run.
//
// Its own module, and never published. A benchmark takes seconds per case and starts a server per
// case, so it must not run in any ordinary suite; keeping it in a published artifact's tests made
// that a matter of remembering a tag rather than a matter of where the code lives.
plugins { id("libtmux.java-library") }

dependencies { testImplementation(project(":libtmux")) }

// Nothing here belongs to `check`: it writes a file and takes seconds. Run it when the table needs
// regenerating.
tasks.named<Test>("test") { enabled = false }

tasks.register<Test>("operationBenchmark") {
    group = "verification"
    description = "Measures what each operation costs and rewrites docs/benchmarks/operations.md."
    val tests = sourceSets.test.get()
    testClassesDirs = tests.output.classesDirs
    classpath = tests.runtimeClasspath
    useJUnitPlatform { includeTags("benchmark") }
    systemProperty("libtmux.tmux", providers.gradleProperty("libtmuxTmux").getOrElse("tmux"))
    systemProperty(
        "libtmux.benchmark.out",
        rootProject.layout.projectDirectory.file("docs/benchmarks/operations.md").asFile.path,
    )
    outputs.upToDateWhen { false }
}
