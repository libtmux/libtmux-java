plugins {
    kotlin("jvm") version "2.4.10"
    application
}

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
