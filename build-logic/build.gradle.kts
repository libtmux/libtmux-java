plugins { `kotlin-dsl` }

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.plugins.errorprone.map { "net.ltgt.gradle:gradle-errorprone-plugin:${it.version}" })
    implementation(libs.plugins.spotless.map { "com.diffplug.spotless:spotless-plugin-gradle:${it.version}" })
    implementation(
        libs.plugins.maven.publish.map { "com.vanniktech:gradle-maven-publish-plugin:${it.version}" }
    )
    implementation(libs.plugins.cyclonedx.map { "org.cyclonedx:cyclonedx-gradle-plugin:${it.version}" })

    // Generates the Java field metamodel from field-catalog.tsv (libtmux.field-catalog.gradle.kts).
    implementation(libs.javapoet)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
