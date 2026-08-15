// The one place a consumer names a version. Every other libtmux coordinate then comes from here,
// which is what stops a project mixing two releases of modules that were built against each other.
//
//     dependencies {
//         implementation(platform("com.git-pull:libtmux-bom:<version>"))
//         implementation("com.git-pull:libtmux")
//         testImplementation("com.git-pull:libtmux-junit5")
//     }
plugins {
    `java-platform`
    id("libtmux.publication")
}

dependencies {
    constraints {
        // Written out rather than derived from the subproject list, so what this repository publishes
        // is a decision someone made here and not a side effect of adding a directory. A new module
        // that belongs in the BOM is one line; one that does not belongs nowhere near it.
        api(project(":libtmux"))
        api(project(":libtmux-jackson"))
        api(project(":libtmux-junit5"))
        api(project(":libtmux-mcp"))
        api(project(":libtmux-workspace"))
    }
}

publishing { publications { create<MavenPublication>("maven") { from(components["javaPlatform"]) } } }
