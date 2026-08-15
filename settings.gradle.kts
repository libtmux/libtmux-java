pluginManagement {
    includeBuild("build-logic")
    repositories { gradlePluginPortal() }
}

// Resolves the JDK 21 toolchain on a host that does not already have one, so a fresh clone and CI
// both build with no prior setup. A host that has it keeps using its own; nothing is downloaded.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "libtmux-java"

include("libtmux")
include("libtmux-bom")
include("libtmux-jackson")
include("libtmux-junit5")
include("libtmux-workspace")
include("libtmux-mcp")
