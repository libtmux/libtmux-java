rootProject.name = "api-example"

includeBuild("libtmux-source") {
    dependencySubstitution {
        substitute(module("io.github.libtmux:libtmux-scala_3"))
            .using(project(":libtmux-scala"))
    }
}
