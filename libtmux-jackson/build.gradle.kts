plugins {
    id("libtmux.published-library")
    id("libtmux.api-diff")
}

dependencies {
    api(project(":libtmux"))
    api(libs.jackson.databind)
}

tasks.jar { manifest { attributes("Automatic-Module-Name" to "io.github.libtmux.jackson") } }
