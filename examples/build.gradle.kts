// Runnable programs, not snippets. Never published.
//
// The README and the guides carry snippets, and `ExamplesTest` compiles those. These are the other
// thing: whole programs with a `main`, short enough to read in one go and real enough to run. The
// suite in this module runs every one of them against a real tmux, so an example cannot quietly stop
// working — which is the failure mode that makes most projects' examples worthless.
plugins { id("libtmux.java-library") }

dependencies {
    implementation(project(":libtmux"))
    implementation(project(":libtmux-workspace"))

    testImplementation(project(":libtmux-junit5"))
}
