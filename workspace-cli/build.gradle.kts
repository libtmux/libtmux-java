import net.ltgt.gradle.errorprone.errorprone

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
    implementation(libs.snakeyaml)

    testImplementation(project(":libtmux-junit5"))
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.installDist)
    systemProperty("workspace.cli.launcher", layout.buildDirectory.file("install/tmux-workspace/bin/tmux-workspace").get().asFile.absolutePath)
}

val compileDevelopmentJava = tasks.register<JavaCompile>("compileDevelopmentJava") {
    group = "application"
    description = "Compiles the CLI for local execution without Error Prone or NullAway."
    source(sourceSets.main.get().allJava)
    classpath = sourceSets.main.get().compileClasspath
    destinationDirectory = layout.buildDirectory.dir("classes/java/development")
    options.errorprone.enabled = false
}

tasks.register<JavaExec>("runDevelopment") {
    group = "application"
    description = "Runs the development classes; normal compilation and checks remain separate."
    dependsOn(tasks.processResources)
    mainClass = application.mainClass
    classpath = files(
        compileDevelopmentJava.flatMap { it.destinationDirectory },
        sourceSets.main.get().output.resourcesDir,
        configurations.runtimeClasspath,
    )
    jvmArgs("-XX:TieredStopAtLevel=1")
}
