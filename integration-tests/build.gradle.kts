// The library's real-tmux suite. Not published, and deliberately not inside any module that is:
// these tests exercise every artifact together, and a suite living in one artifact's test source
// set makes that artifact's dependencies and lifecycle answerable for how the whole library is
// tested.
plugins {
    id("libtmux.java-library")
    id("libtmux.tmux-matrix")
}

dependencies {
    testImplementation(project(":libtmux"))
    testImplementation(project(":libtmux-jackson"))
    testImplementation(project(":libtmux-junit5"))
}
