// A CycloneDX SBOM for a module with a runtime dependency, published alongside its jar.
//
// Separate from libtmux.published-library so a module that cannot apply libtmux.java-library — the
// Kotlin facade compiles Kotlin, not Java — still gets one. Applied after libtmux.publication, whose
// maven-publish plugin the artifact below is attached through.
//
// `the<PublishingExtension>()` rather than the `publishing` accessor: the latter is generated only
// for a script that applies a publishing plugin itself, and this one deliberately does not — it
// relies on the consuming module having applied libtmux.publication first.
import org.gradle.api.publish.PublishingExtension
import org.gradle.kotlin.dsl.the

plugins { id("org.cyclonedx.bom") }

// Scoped to runtimeClasspath: a compileOnly annotation such as error_prone_annotations is never on a
// consumer's classpath, and listing it would misstate what this jar actually pulls in at runtime.
val cyclonedxBom = tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("runtimeClasspath"))
}

// A consumer inspects what they are pulling in before it reaches Central, the same reason the jar
// itself is checked rather than trusted on the way out. The task has two outputs (XML and JSON), so
// the artifact names the file rather than the task, which Gradle can only default for a single one.
the<PublishingExtension>().publications.withType<MavenPublication>().configureEach {
    artifact(cyclonedxBom.flatMap { it.jsonOutput }) {
        classifier = "cyclonedx"
        extension = "json"
    }
}
