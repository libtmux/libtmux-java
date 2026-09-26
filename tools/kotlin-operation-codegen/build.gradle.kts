// Generates the Kotlin suspend mirror and companion-hosted field namespace for libtmux-kotlin from
// operation-catalog.json (see docs at ../../docs/spikes for the schema this reads). Never published:
// :libtmux-kotlin runs it as a build step through generateOperationWrappers, the same JavaExec
// pattern compileOldestConsumer already uses for the Kotlin compiler.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

application {
    mainClass = "io.github.libtmux.codegen.kotlin.MainKt"
}

dependencies {
    implementation(libs.kotlinpoet)
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

tasks.withType<Javadoc>().configureEach { enabled = false }
