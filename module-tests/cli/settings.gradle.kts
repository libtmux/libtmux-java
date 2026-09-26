// A build of its own, so it sees tmux-workspace only as a user's launcher would: through the
// published coordinates, staged by `./gradlew publishAllPublicationsToStagingRepository` in the
// root build.
rootProject.name = "staged-cli-consumer"

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
