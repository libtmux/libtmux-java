plugins { application }

// The version the root build staged. Required, so a consumer that resolved some other release
// cannot pass for this one.
val libtmuxVersion = providers.gradleProperty("libtmuxVersion").get()

dependencies { implementation("io.github.libtmux:libtmux:$libtmuxVersion") }

tasks.compileJava { options.release = 25 }

tasks.named<JavaExec>("run") { systemProperty("libtmux.expectedVersion", libtmuxVersion) }

application {
    mainModule = "io.github.libtmux.consumer"
    mainClass = "io.github.libtmux.consumer.Main"
}
