// Aggregate entry points, so the gate is one command whatever the module layout becomes.

/**
 * What this repository releases, checked rather than agreed.
 *
 * A module is published exactly when it applies `libtmux.published-library`, and the platform is
 * what a consumer uses to name one version for all of them. Those two facts have to agree, and
 * nothing in the language makes them: adding a module and forgetting the platform yields an artifact
 * on Central that the platform does not manage, which a consumer discovers as a version they have to
 * pin by hand for no stated reason.
 *
 * Directory names cannot carry this. A convention that published modules live somewhere particular
 * is unverifiable; this is a build failure.
 */
val platformCoversEveryPublishedModule =
    tasks.register("platformCoversEveryPublishedModule") {
        group = "verification"
        description = "Fails when a published module is missing from libtmux-bom, or vice versa."

        val platform = project(":libtmux-bom")
        // Read at configuration time, so the task's own action holds values rather than projects.
        //
        // A module counts as published when it actually declares a publication, not when it merely
        // applies the plugin. Applying maven-publish and declaring nothing produces a module that
        // looks published to a build script and releases no artifact — which is exactly what
        // libtmux-kotlin did until a publishToMavenLocal noticed the jar was missing.
        val published = provider {
            subprojects
                .filter { it != platform }
                .filter { candidate ->
                    candidate.extensions
                        .findByType(PublishingExtension::class.java)
                        ?.publications
                        ?.withType(MavenPublication::class.java)
                        ?.isNotEmpty() == true
                }
                .map { it.name }
                .toSortedSet()
        }
        val managed = provider {
            platform.configurations
                .getByName("api")
                .dependencyConstraints
                .map { it.name }
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
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn(platformCoversEveryPublishedModule, kotlinStaysDownstream)
}
