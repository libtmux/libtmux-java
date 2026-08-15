// Publication conventions: one coherent set of coordinates and metadata across every module.
plugins {
    id("libtmux.java-library")
    `maven-publish`
}

group = "com.git-pull"
version = providers.gradleProperty("libtmuxVersion").getOrElse("0.1.0-SNAPSHOT")

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = project.name
                description = "Typed tmux access for the JVM"
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
                // This port, not the Python library it is a sibling of. A published artifact whose
                // scm points at another project sends anyone reading its metadata to the wrong
                // source, and every tool that follows it — a decompiler, a licence scanner, an IDE
                // fetching sources — lands there too.
                scm {
                    url = "https://github.com/libtmux/libtmux-java"
                    connection = "scm:git:https://github.com/libtmux/libtmux-java.git"
                    developerConnection = "scm:git:ssh://git@github.com/libtmux/libtmux-java.git"
                }
            }
        }
    }
}
