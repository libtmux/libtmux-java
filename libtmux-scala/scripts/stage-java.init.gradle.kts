import org.gradle.api.publish.PublishingExtension

val scalaStage = System.getenv("LIBTMUX_JAVA_REPOSITORY")?.let { java.io.File(it) }
require(scalaStage == null || scalaStage.isAbsolute) {
    "LIBTMUX_JAVA_REPOSITORY must be an absolute local directory, not a URL"
}

allprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "ScalaDev"
                url = uri(scalaStage ?: rootProject.file("libtmux-scala/target/java-repository"))
            }
        }
    }
}
