// The libtmux.* convention plugins. A module script applies one and declares only what differs.
plugins { `kotlin-dsl` }

dependencies {
    implementation(project(":codegen"))

    implementation(libs.plugins.errorprone.map { "net.ltgt.gradle:gradle-errorprone-plugin:${it.version}" })
    implementation(libs.plugins.spotless.map { "com.diffplug.spotless:spotless-plugin-gradle:${it.version}" })
    implementation(
        libs.plugins.maven.publish.map { "com.vanniktech:gradle-maven-publish-plugin:${it.version}" }
    )
    implementation(libs.plugins.cyclonedx.map { "org.cyclonedx:cyclonedx-gradle-plugin:${it.version}" })
}
