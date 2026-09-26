// Measures what an operation costs, and rewrites docs/benchmarks/operations.md from a real run.
//
// Its own module, and never published. A benchmark takes seconds per case and starts a server per
// case, so it must not run in any ordinary suite; keeping it in a published artifact's tests made
// that a matter of remembering a tag rather than a matter of where the code lives.
plugins { id("libtmux.scala-library") }

dependencies {
    testImplementation(project(":libtmux"))
    implementation(project(":libtmux-scala"))
    implementation(project(":libtmux-scala-cats"))
}

// No measurement belongs to `check`: each writes a file and takes seconds. Run them when the table
// needs regenerating. The ordinary suite keeps only the harness's own tests.
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("benchmark") } }

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

// The Scala facades' modes against each other on one live tmux: Java process reads, direct-style
// blocking reads, Cats serial and bounded reads, batches, chains, admitted control requests, polling
// and pushed output. Writes raw JSON samples wherever it is told; a run describes the machine it ran
// on, so nothing is committed.
//
//   ./gradlew :benchmarks:scalaModeBenchmark \
//       -PlibtmuxTmux=/path/to/tmux -PscalaBenchSocket=/tmp/libtmux-java-dev/bench/s \
//       -PscalaBenchConfig=/tmp/libtmux-java-dev/bench/empty.conf -PscalaBenchOut=/tmp/libtmux-java-dev/bench/modes.json
tasks.register<JavaExec>("scalaModeBenchmark") {
    group = "verification"
    description = "Measures the Scala facades' modes against each other and writes raw JSON samples."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.libtmux.scaladsl.benchmarks.ModeBenchmarks"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val required = listOf("libtmuxTmux", "scalaBenchSocket", "scalaBenchConfig", "scalaBenchOut")
    argumentProviders.add(CommandLineArgumentProvider {
        required.map { name ->
            requireNotNull(providers.gradleProperty(name).orNull) { "scalaModeBenchmark needs -P$name" }
        }
    })
    outputs.upToDateWhen { false }
}
