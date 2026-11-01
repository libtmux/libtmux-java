// The in-process half of the tmux quarantine: a JUnit Platform listener that refuses an unsandboxed run.
// Held to the product modules' compiler bar, whose settings a project in this build cannot apply to itself.
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
    testImplementation(libs.junit.platform.launcher)
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
