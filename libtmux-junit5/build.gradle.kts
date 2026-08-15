plugins {
    id("libtmux.published-library")
    id("libtmux.tmux-matrix")
}

dependencies {
    api(project(":libtmux"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)

    // The extension's own gates run a nested engine, because the only honest proof that teardown
    // survives a failing test is to execute one and inspect what it left behind.
    testImplementation(libs.junit.platform.testkit)
}

tasks.jar { manifest { attributes("Automatic-Module-Name" to "io.github.libtmux.junit5") } }
