// The build's own tooling, kept out of the product modules' way: convention plugins, the code
// generators they run, and the javadoc Doclet that writes the operation catalog those generators read.
pluginManagement { repositories { gradlePluginPortal() } }

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"

include("conventions")
include("codegen")
include("catalog-doclet")
