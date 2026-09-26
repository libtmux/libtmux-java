// A build of its own, so it sees the Scala facades only as a consumer would: through the published
// coordinates, staged by `./gradlew publishAllPublicationsToStagingRepository` in the root build.
rootProject.name = "staged-scala-consumer"

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

// Separate projects, so each runtime classpath is what that artifact alone brings.
include("core")
include("cats")
