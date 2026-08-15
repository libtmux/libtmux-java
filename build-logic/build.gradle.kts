plugins { `kotlin-dsl` }

repositories { gradlePluginPortal() }

dependencies {
    implementation(libs.plugins.errorprone.map { "net.ltgt.gradle:gradle-errorprone-plugin:${it.version}" })
    implementation(libs.plugins.spotless.map { "com.diffplug.spotless:spotless-plugin-gradle:${it.version}" })
    implementation(
        libs.plugins.maven.publish.map { "com.vanniktech:gradle-maven-publish-plugin:${it.version}" }
    )
}
