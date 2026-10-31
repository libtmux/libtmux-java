plugins {
    kotlin("jvm") version "2.4.10"
    application
}

// A build of its own, so the quarantine is applied by path rather than as a plugin.
apply(from = rootProject.file("../../build-logic/conventions/src/main/kotlin/libtmux.tmux-quarantine.gradle.kts"))

// The version the root build staged. Required, so a consumer that resolved some other release
// cannot pass for this one.
val libtmuxVersion = providers.gradleProperty("libtmuxVersion").get()

dependencies {
    // As the README's install block says to: the BOM picks every libtmux version.
    implementation(platform("io.github.libtmux:libtmux-bom:$libtmuxVersion"))
    implementation("io.github.libtmux:libtmux-kotlin")
}

kotlin { jvmToolchain(25) }

application { mainClass = "io.github.libtmux.consumer.MainKt" }

@Suppress("UNCHECKED_CAST")
val quarantineTmux = extra["quarantineTmux"] as (TaskProvider<JavaExec>) -> Unit

quarantineTmux(tasks.named<JavaExec>("run"))
