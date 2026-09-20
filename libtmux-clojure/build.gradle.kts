plugins { id("libtmux.clojure-library") }

extra["clojureTestNamespaces"] =
    "libtmux.async-test,libtmux.control-test,libtmux.core-test,libtmux.data-test,libtmux.internal.package-test"

dependencies { api(project(":libtmux")) }

tasks.withType<JavaExec>().configureEach {
    systemProperty(
        "libtmux.clojure.main-namespaces",
        "libtmux.core,libtmux.data,libtmux.async,libtmux.control",
    )
}
