// Runnable programs, not snippets. Never published.
//
// The README and the guides carry snippets, and `DocumentationSnippetsTest` runs those. These are the other
// thing: whole programs with a `main`, short enough to read in one go and real enough to run. The
// suite in this module runs every one of them against a real tmux, so an example cannot quietly stop
// working — which is the failure mode that makes most projects' examples worthless.
plugins {
    // Java first; one program is Kotlin, to show the Flow and coroutine adapter end to end; four are
    // Scala, for the direct and Cats facades. The Scala convention carries the Java one.
    id("libtmux.scala-library")
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation(project(":libtmux"))
    implementation(project(":libtmux-workspace"))
    implementation(project(":libtmux-kotlin"))
    implementation(project(":libtmux-scala"))
    implementation(project(":libtmux-scala-cats"))

    // Embedding libtmux-mcp means supplying the transport, which means supplying its JSON mapper.
    implementation(project(":libtmux-mcp"))
    implementation(libs.jackson.databind)
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson2)

    testImplementation(project(":libtmux-junit5"))
    testImplementation(testFixtures(project(":integration-tests")))
}
