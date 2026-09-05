plugins {
    id("libtmux.published-library")
    id("libtmux.tmux-matrix")
    application
}

// An MCP client launches this as a subprocess and speaks JSON-RPC over its standard streams, so it
// needs a real launcher rather than a library jar. installDist writes one with its own lib
// directory; no stdout but the protocol may reach the client.
application {
    mainClass = "io.github.libtmux.mcp.Main"
    applicationName = "libtmux-mcp"
}

// What the launcher runs with and the library does not publish. The SDK logs through SLF4J, and a
// launcher speaking a protocol on stdout should not greet its client with provider warnings on
// stderr; an application embedding this module picks its own provider and must not inherit one.
val launcherRuntime = configurations.register("launcherRuntime")

configurations.runtimeClasspath { extendsFrom(launcherRuntime.get()) }

dependencies {
    api(project(":libtmux"))

    // On this module's own signature: serving() takes a transport provider and every entry point
    // returns the SDK's server, so compiling against this module means compiling against the SDK.
    api(libs.mcp.core)

    implementation(libs.mcp.json.jackson2)
    implementation(libs.jackson.databind)
    implementation(libs.re2j)

    add(launcherRuntime.name, libs.slf4j.nop)

    testImplementation(project(":libtmux-junit5"))
}

tasks.jar { manifest { attributes("Automatic-Module-Name" to "io.github.libtmux.mcp") } }
