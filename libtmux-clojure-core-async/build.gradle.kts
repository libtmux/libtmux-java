plugins { id("libtmux.clojure-library") }

extra["clojureTestNamespaces"] =
    "libtmux.core-async-test,libtmux.internal.core-async-package-test"

dependencies {
    api(project(":libtmux-clojure"))
    api(libs.core.async)
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("libtmux.clojure.main-namespaces", "libtmux.core-async")
}
