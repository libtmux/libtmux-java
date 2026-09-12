plugins {
    id("libtmux.java-library")
    id("libtmux.tmux-matrix")
    application
}

version = providers.gradleProperty("libtmuxVersion").get()

application {
    mainClass = "io.github.libtmux.workspace.cli.Main"
    applicationName = "tmux-workspace"
}

distributions.main { contents { from("README.md") } }

dependencies {
    implementation(project(":libtmux-workspace"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    implementation(libs.picocli)

    testImplementation(project(":libtmux-junit5"))
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.installDist)
    systemProperty("workspace.cli.launcher", layout.buildDirectory.file("install/tmux-workspace/bin/tmux-workspace").get().asFile.absolutePath)
}
