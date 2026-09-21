// Declared here so every module that publishes loads the plugin in one classloader scope. Applying
// it in two sibling projects that differ in which other plugins they carry puts its shared build
// service in two scopes, and Gradle refuses to wire them together.
plugins {
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Aggregate entry points, so the gate is one command whatever the module layout becomes.

// Scala publications are declared independently of Gradle; sbt verifies the same declaration
// against its generated publications. This gate does not require sbt or staged artifacts.
val platformCoversEveryPublishedModule =
    tasks.register("platformCoversEveryPublishedModule") {
        group = "verification"
        description = "Fails when a published module is missing from libtmux-bom, or vice versa."

        val platform = project(":libtmux-bom")
        val scalaPublications = layout.projectDirectory.file("libtmux-scala/publications.txt").asFile
        inputs.file(scalaPublications)
        val published = provider {
            subprojects
                .filter { it != platform }
                .flatMap { candidate ->
                    candidate.extensions
                        .findByType(PublishingExtension::class.java)
                        ?.publications
                        ?.withType(MavenPublication::class.java)
                        ?.map { "${it.groupId}:${it.artifactId}:${it.version}" }
                        ?: emptyList()
                }
                .toSortedSet()
        }
        val declared = provider {
            val rows = scalaPublications.readLines()
            val coordinate = Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")
            require(rows.isNotEmpty()) { "libtmux-scala/publications.txt must declare at least one publication." }
            rows.forEachIndexed { index, row ->
                require(coordinate.matches(row)) {
                    "libtmux-scala/publications.txt:${index + 1} must contain one group:artifact coordinate."
                }
            }
            require(rows.size == rows.toSet().size) { "libtmux-scala/publications.txt contains duplicate coordinates." }
            rows.map { "$it:${platform.version}" }.toSortedSet()
        }
        val managed = provider {
            platform.configurations
                .getByName("api")
                .dependencyConstraints
                .map { "${it.group}:${it.name}:${it.version}" }
                .toSortedSet()
        }

        doLast {
            val shipped = published.get() + declared.get()
            val listed = managed.get()
            require(shipped == listed) {
                buildString {
                    appendLine("libtmux-bom does not manage what this repository publishes.")
                    (shipped - listed).forEach { appendLine("  published but not in the platform: $it") }
                    (listed - shipped).forEach { appendLine("  in the platform but not published: $it") }
                }
            }
            logger.lifecycle("libtmux-bom manages all ${shipped.size} published modules")
        }
    }

/**
 * Nothing written in Java may depend on the Kotlin module.
 *
 * Per the JSpecify specification a class carrying `@kotlin.Metadata` is not null-marked, because the
 * Kotlin compiler does not yet emit full nullness information into binaries (KT-47417). A
 * Kotlin-authored API is therefore strictly worse for a Java consumer and invisible to NullAway, so
 * the sugar is allowed to depend on the library and never the other way round.
 *
 * Stated as a build failure rather than as a paragraph somebody has to have read.
 */
val kotlinStaysDownstream =
    tasks.register("kotlinStaysDownstream") {
        group = "verification"
        description = "Fails when a module that is not itself Kotlin depends on libtmux-kotlin."

        val offenders = provider {
            subprojects
                .filter { it.name != "libtmux-kotlin" && !it.plugins.hasPlugin("org.jetbrains.kotlin.jvm") }
                .filter { candidate ->
                    candidate.configurations.any { configuration ->
                        configuration.dependencies.any { it.name == "libtmux-kotlin" }
                    }
                }
                .map { it.path }
        }

        doLast {
            val found = offenders.get()
            require(found.isEmpty()) {
                "these are not Kotlin and must not depend on libtmux-kotlin: ${found.joinToString(", ")}"
            }
            logger.lifecycle("libtmux-kotlin is depended on by nothing that would lose its nullness")
        }
    }

tasks.register("check") {
    group = "verification"
    description = "Every gate that must hold before publication."
    dependsOn(subprojects.filter { it.buildFile.exists() }.map { "${it.path}:check" })
    dependsOn(platformCoversEveryPublishedModule, kotlinStaysDownstream)
}
