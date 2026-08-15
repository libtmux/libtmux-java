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
                        id = "tmux-python"
                        name = "tmux-python contributors"
                        url = "https://github.com/tmux-python"
                    }
                }
                scm {
                    url = "https://github.com/tmux-python/libtmux"
                    connection = "scm:git:https://github.com/tmux-python/libtmux.git"
                    developerConnection = "scm:git:ssh://git@github.com/tmux-python/libtmux.git"
                }
            }
        }
    }
}
