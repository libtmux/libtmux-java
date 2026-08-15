plugins {
    id("libtmux.published-library")
    id("libtmux.tmux-matrix")
}

dependencies {
    api(project(":libtmux"))
    implementation(libs.jackson.yaml)

    testImplementation(project(":libtmux-junit5"))
}

tasks.jar { manifest { attributes("Automatic-Module-Name" to "com.git_pull.libtmux.workspace") } }
