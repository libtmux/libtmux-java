plugins { id("libtmux.published-library") }

dependencies {
    api(project(":libtmux"))
    implementation(libs.jackson.databind)
}

tasks.jar { manifest { attributes("Automatic-Module-Name" to "com.git_pull.libtmux.jackson") } }
