plugins { application }

// A build of its own, so the quarantine is applied by path rather than as a plugin.
apply(from = rootProject.file("../../build-logic/conventions/src/main/kotlin/libtmux.tmux-quarantine.gradle.kts"))

// The version the root build staged. Required, so a consumer that resolved some other release
// cannot pass for this one.
val libtmuxVersion = providers.gradleProperty("libtmuxVersion").get()

dependencies { implementation("io.github.libtmux:libtmux:$libtmuxVersion") }

tasks.compileJava { options.release = 25 }

@Suppress("UNCHECKED_CAST")
val quarantineTmux = extra["quarantineTmux"] as (TaskProvider<JavaExec>) -> Unit

quarantineTmux(tasks.named<JavaExec>("run") { systemProperty("libtmux.expectedVersion", libtmuxVersion) })

application {
    mainModule = "io.github.libtmux.consumer"
    mainClass = "io.github.libtmux.consumer.Main"
}
