import org.gradle.api.publish.PublishingExtension

val bomStage = System.getenv("LIBTMUX_BOM_REPOSITORY")?.let { java.io.File(it) }
require(bomStage == null || bomStage.isAbsolute) {
    "LIBTMUX_BOM_REPOSITORY must be an absolute local directory, not a URL"
}

allprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "ScalaBom"
                url = uri(bomStage ?: rootProject.file("scala/target/bom-repository"))
            }
        }
    }
}
