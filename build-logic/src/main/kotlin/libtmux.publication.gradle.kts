// Coordinates and POM metadata, for anything this repository publishes.
//
// Separate from the library conventions because the BOM is not a library: it applies `java-platform`,
// which cannot coexist with `java-library`, and it still has to carry identical metadata. Anything
// that differed between the two — a licence, an scm url — would be a difference a consumer's tooling
// notices and nobody meant.
plugins { `maven-publish` }

group = "io.github.libtmux"
version = providers.gradleProperty("libtmuxVersion").getOrElse("0.0.1-alpha.1-SNAPSHOT")

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = project.name
            description = "Typed tmux access for the JVM. Alpha: the API is not stable and will change without notice."
            url = "https://libtmux.git-pull.com/"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/licenses/MIT"
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
}
