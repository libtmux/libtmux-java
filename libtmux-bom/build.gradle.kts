// The one place a consumer names a BOM version. Every BOM-managed libtmux coordinate then comes
// from here, which is what stops a project mixing two releases of artifacts built against each other.
//
//     dependencies {
//         implementation(platform("io.github.libtmux:libtmux-bom:<version>"))
//         implementation("io.github.libtmux:libtmux")
//         testImplementation("io.github.libtmux:libtmux-junit5")
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
        api(project(":libtmux-kotlin"))
        api(project(":libtmux-junit5"))
        api(project(":libtmux-mcp"))
        api(project(":libtmux-workspace"))
        // The Scala facades release with the Java artifacts, at the same version.
        api(project(":libtmux-scala"))
        api(project(":libtmux-scala-cats"))
        api(project(":libtmux-scala-ox"))
    }
}
