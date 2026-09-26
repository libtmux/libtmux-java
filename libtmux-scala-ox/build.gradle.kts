// An Ox Flow over a subscription or a live view, and a supervised scenario, for direct-style
// structured concurrency on virtual threads.
plugins { id("libtmux.published-scala-library") }

mavenPublishing { pom { description = "An Ox Flow and live view over libtmux's direct-style facade." } }

dependencies {
    api(project(":libtmux-scala"))
    api(libs.ox.core)
}
