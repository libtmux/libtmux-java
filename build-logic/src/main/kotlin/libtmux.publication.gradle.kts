// Coordinates, metadata and the route to Maven Central, for anything this repository publishes.
//
// Separate from the library conventions because the BOM is not a library: it applies `java-platform`,
// which cannot coexist with `java-library`, and it still has to carry identical metadata. Anything
// that differed between the two — a licence, an scm url — would be a difference a consumer's tooling
// notices and nobody meant.
//
// The publisher is com.vanniktech.maven.publish rather than bare `maven-publish` because Central is
// no longer reached by deploying to a repository URL. OSSRH reached end of life on 30 June 2025 and
// the Portal has an upload API of its own, for which Sonatype ships no first-party Gradle plugin.
// Anything written against `oss.sonatype.org`, gradle-nexus/publish-plugin included, targets a
// service that is gone.
plugins { id("com.vanniktech.maven.publish") }

group = "io.github.libtmux"
version = providers.gradleProperty("libtmuxVersion").getOrElse("0.0.1-alpha.1-SNAPSHOT")

// Supplied by CI as ORG_GRADLE_PROJECT_signingInMemoryKey, and absent on a developer's machine. A
// build that demanded it everywhere could not run publishToMavenLocal, which is how a publication
// gets checked before anything reaches Central.
val signingKey = providers.gradleProperty("signingInMemoryKey")

mavenPublishing {
    // There is no host to choose any more: this plugin dropped SonatypeHost in favour of the Portal
    // being the only route, which is the clearest statement available that OSSRH is gone.
    //
    // automaticRelease stays false. A deployment can be dropped while it is pending; a release cannot
    // be unpublished, and the first thing this project sends to Central should be looked at first.
    publishToMavenCentral(automaticRelease = false)

    if (signingKey.isPresent) {
        signAllPublications()
    }

    coordinates(group.toString(), project.name, version.toString())

    pom {
        name = project.name
        description = "Typed tmux access for the JVM. Alpha: the API is not stable and will change without notice."
        url = "https://libtmux.git-pull.com/"
        inceptionYear = "2026"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "libtmux"
                name = "libtmux contributors"
                url = "https://github.com/libtmux"
            }
        }
        // This port, not the Python library it is a sibling of. A published artifact whose scm
        // points at another project sends anyone reading its metadata to the wrong source, and
        // every tool that follows it — a decompiler, a licence scanner, an IDE fetching sources
        // — lands there too.
        scm {
            url = "https://github.com/libtmux/libtmux-java"
            connection = "scm:git:https://github.com/libtmux/libtmux-java.git"
            developerConnection = "scm:git:ssh://git@github.com/libtmux/libtmux-java.git"
        }
    }
}
