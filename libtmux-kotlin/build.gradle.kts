// Kotlin ergonomics over the Java API. Sugar, not enablement: the core is already null-safe from
// Kotlin because it is annotated with JSpecify, which Kotlin has read since 1.5.20.
//
// Nothing written in Java may depend on this module. Per the JSpecify specification a class carrying
// `@kotlin.Metadata` is not null-marked — the Kotlin compiler does not emit full nullness into
// binaries yet (KT-47417) — so a Kotlin-authored API is strictly worse for a Java consumer and for
// NullAway than a Java-authored one. The root build fails if that dependency ever appears.
plugins {
    id("libtmux.publication")
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    // Every public declaration states its visibility and its return type. A library's ABI should not
    // be something the compiler inferred.
    explicitApi()
    compilerOptions {
        // The whole point of this module is that the Java API's nullness is real. Strict mode turns
        // a mismatch against a @NullMarked type into an error here, so this module compiling is
        // itself evidence that the annotations downstairs are correct.
        freeCompilerArgs.addAll("-Xjspecify-annotations=strict")
    }
}

dependencies {
    api(project(":libtmux"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    withSourcesJar()
    withJavadocJar()
}

// The publication convention configures publications; it does not invent one. Declared here rather
// than inherited from libtmux.published-library, which brings the Java conventions this module has
// no Java to apply them to.
publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// Reproducible archives, as every other module here publishes them.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("755") }
    filePermissions { unix("644") }
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "com.git_pull.libtmux.kotlin",
            "Implementation-Version" to provider { project.version.toString() },
        )
    }
}
