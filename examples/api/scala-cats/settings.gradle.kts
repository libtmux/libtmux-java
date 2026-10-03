rootProject.name = "api-example"

includeBuild("libtmux-source") {
    dependencySubstitution {
        substitute(module("io.github.libtmux:libtmux-scala-cats_3"))
            .using(project(":libtmux-scala-cats"))
    }
}
