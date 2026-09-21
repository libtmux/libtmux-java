// Declared here so every module that publishes loads the plugin in one classloader scope. Applying
// it in two sibling projects that differ in which other plugins they carry puts its shared build
// service in two scopes, and Gradle refuses to wire them together.
plugins {
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Aggregate entry points, so the gate is one command whatever the module layout becomes.

// The BOM covers Gradle publications. Scala artifacts release independently, and
// their shared sbt build verifies the cross-published coordinate manifest.
val platformCoversEveryPublishedModule =
    tasks.register("platformCoversEveryPublishedModule") {
        group = "verification"
        description = "Fails when a Gradle publication is missing from libtmux-bom, or vice versa."

        val platform = project(":libtmux-bom")
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
        val managed = provider {
            platform.configurations
                .getByName("api")
                .dependencyConstraints
                .map { "${it.group}:${it.name}:${it.version}" }
                .toSortedSet()
        }

        doLast {
            val shipped = published.get()
            val listed = managed.get()
            require(shipped == listed) {
                buildString {
                    appendLine("libtmux-bom does not manage what this repository publishes.")
                    (shipped - listed).forEach { appendLine("  published but not in the platform: $it") }
                    (listed - shipped).forEach { appendLine("  in the platform but not published: $it") }
                }
            }
            logger.lifecycle("libtmux-bom manages all ${shipped.size} Gradle-published artifacts")
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
