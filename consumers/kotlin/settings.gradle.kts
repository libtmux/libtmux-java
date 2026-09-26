// A build of its own, so it sees libtmux-kotlin only as a consumer would: through the BOM and the
// published coordinates, staged by `./gradlew publishAllPublicationsToStagingRepository`.
pluginManagement { repositories { gradlePluginPortal() } }

rootProject.name = "staged-kotlin-consumer"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        maven {
            url = uri(providers.gradleProperty("libtmuxStaging").getOrElse("../../build/staging-repository"))
            content { includeGroup("io.github.libtmux") }
        }
        mavenCentral { content { excludeGroup("io.github.libtmux") } }
    }
}
