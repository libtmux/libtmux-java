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
    // The core, named directly. It used to arrive through :libtmux-workspace, whose own classes no
    // source here imports: the two are separate implementations with different document languages,
    // and shipping that jar in the distribution only claimed otherwise.
    implementation(project(":libtmux"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    implementation(libs.picocli)
    implementation(libs.snakeyaml)

    testImplementation(project(":libtmux-junit5"))
}

// Only the tests tagged "distribution" run the installed launcher, so only the tasks that run them
// assemble it. Keeping the assembly out of `test` keeps an application build out of the edit loop.
val distributionTest = tasks.register<Test>("distributionTest") {
    group = "verification"
    description = "Runs the tests that drive the installed tmux-workspace launcher."
    val tests = sourceSets.test.get()
    testClassesDirs = tests.output.classesDirs
    classpath = tests.runtimeClasspath
    useJUnitPlatform { includeTags("distribution") }
}

tasks.test { useJUnitPlatform { excludeTags("distribution") } }

tasks.check { dependsOn(distributionTest) }

tasks.withType<Test>().configureEach {
    // Every task but `test` runs the tagged tests: the matrix lanes run the whole suite per tmux
    // release, and `distributionTest` runs nothing else.
    if (name != "test") dependsOn(tasks.installDist)
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
