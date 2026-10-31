// The in-process half of the tmux quarantine: a JUnit Platform listener that stops a test run whose
// environment could reach a tmux server the run was not given, such as one an IDE started. Modules
// put it on their test runtime classpath; the root build fails when one with tests does not.
//
// Held to the product modules' compiler bar. Those settings live in libtmux.java-library, which a
// project inside this build cannot apply to itself, so the few that matter are repeated here.
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spotless)
}

// The coordinates every module resolves this by; the root settings substitute this project.
group = "io.github.libtmux.build"

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    compileOnly(libs.jspecify)
    compileOnly(platform(libs.junit.bom))
    compileOnly(libs.junit.platform.launcher)
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testCompileOnly(libs.jspecify)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    options.errorprone {
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:JSpecifyMode", "true")
        option("NullAway:AnnotatedPackages", "io.github.libtmux")
    }
}

spotless {
    java {
        palantirJavaFormat(libs.versions.palantir.format.get())
        removeUnusedImports()
        endWithNewline()
    }
}

tasks.test { useJUnitPlatform() }

// This project's own tests load the listener it ships, so they run under the quarantine too. The
// plugin is built by a sibling project, so it is applied by path.
apply(from = "../conventions/src/main/kotlin/libtmux.tmux-quarantine.gradle.kts")
