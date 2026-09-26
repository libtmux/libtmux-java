// The version the root build staged. Required, so a consumer that resolved some other release cannot
// pass for this one. The BOM picks every libtmux version, as the README's install block says to.
val libtmuxVersion = providers.gradleProperty("libtmuxVersion").get()

subprojects {
    apply(plugin = "scala")
    apply(plugin = "application")

    extensions.configure<JavaPluginExtension> { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

    dependencies { "implementation"(platform("io.github.libtmux:libtmux-bom:$libtmuxVersion")) }

    tasks.withType<ScalaCompile>().configureEach {
        scalaCompileOptions.additionalParameters = listOf("-release:25", "-deprecation", "-feature", "-Werror")
    }
}

project(":core") {
    dependencies { "implementation"("io.github.libtmux:libtmux-scala_3") }
    extensions.configure<JavaApplication> { mainClass = "io.github.libtmux.consumer.CoreConsumer" }
}

project(":cats") {
    dependencies {
        "implementation"("io.github.libtmux:libtmux-scala-cats_3")
        "implementation"("io.github.libtmux:libtmux-scala-ox_3")
    }
    extensions.configure<JavaApplication> { mainClass = "io.github.libtmux.consumer.CatsConsumer" }
}
