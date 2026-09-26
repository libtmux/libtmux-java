// A javadoc Doclet that reads :libtmux's own sources and emits operation-catalog.json (see
// libtmux/build.gradle.kts, generateOperationCatalog). It runs inside the javadoc tool, on nothing
// but the JDK: it classifies operations by annotation name, never loading :libtmux's classes.
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

// The coordinates :libtmux resolves this Doclet by; the root settings substitute this project.
group = "io.github.libtmux.build"

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    compileOnly(libs.jspecify)
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)
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
