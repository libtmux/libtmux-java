// Shared Scala 3 conventions, on top of the Java ones: the same toolchain, reproducible archives and
// tmux quarantine for every test task, plus the Scala compiler's own strictness and scalafmt.
//
// munit suites run on the JUnit Platform through the vintage engine, beside any JUnit 5 suite in the
// same module, so one Test task configuration covers both.
import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    id("libtmux.java-library")
    scala
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // The facades' signatures name Scala library types, so a consumer compiles against them too.
    "api"(libs.findLibrary("scala3-library").orElseThrow())
    "testImplementation"(libs.findLibrary("munit").orElseThrow())
    "testRuntimeOnly"(libs.findLibrary("junit-vintage-engine").orElseThrow())
}

tasks.withType<ScalaCompile>().configureEach {
    scalaCompileOptions.additionalParameters = listOf(
        "-deprecation",
        "-feature",
        "-unchecked",
        "-Werror",
        "-release:25",
        "-language:strictEquality",
        // TASTy records source positions; relative to the repository, a published jar names no path
        // on the machine that built it, and two machines build the same bytes.
        "-sourceroot",
        rootDir.absolutePath,
    )
}

extensions.configure<SpotlessExtension> {
    scala {
        // Hand-written sources only: generated ones are not formatted, and not a human's choice.
        target("src/**/*.scala")
        scalafmt(libs.findVersion("scalafmt").orElseThrow().requiredVersion)
            .configFile(rootProject.file(".scalafmt.conf"))
    }
}
