plugins { id("libtmux.published-library") }

dependencies {
    api(project(":libtmux"))
    api(libs.jackson.databind)
}

tasks.jar { manifest { attributes("Automatic-Module-Name" to "io.github.libtmux.jackson") } }
