// A module that is both a Java library and a published artifact. Coordinates, metadata and the route
// to Central all live in the publication convention, which the BOM shares.
//
// No publication is declared here: com.vanniktech.maven.publish detects the module's shape and
// creates one. Declaring another would publish the same artifact twice.
//
// CycloneDX is here rather than on the shared publication convention: libtmux-bom applies that one
// too, and a `java-platform` has no runtimeClasspath to describe — its SBOM would be an empty
// document that only looked like one.
plugins {
    id("libtmux.java-library")
    id("libtmux.publication")
    id("org.cyclonedx.bom")
}

// Scoped to runtimeClasspath: a compileOnly annotation such as error_prone_annotations is never on a
// consumer's classpath, and listing it would misstate what this jar actually pulls in at runtime.
val cyclonedxBom = tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("runtimeClasspath"))
}

// A consumer inspects what they are pulling in before it reaches Central, the same reason the jar
// itself is checked rather than trusted on the way out. The task has two outputs (XML and JSON), so
// the artifact names the file rather than the task, which Gradle can only default for a single one.
publishing.publications.withType<MavenPublication>().configureEach {
    artifact(cyclonedxBom.flatMap { it.jsonOutput }) {
        classifier = "cyclonedx"
        extension = "json"
    }
}
