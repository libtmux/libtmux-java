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

dependencies {
    api(project(":libtmux"))
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson2)
    implementation(libs.jackson.databind)

    // A model sends a filter as the versioned JSON document, which is what this module reads it
    // from. api rather than implementation: the filter type appears on the catalog's own signature.
    api(project(":libtmux-jackson"))

    // A whole session described in one document, which is what tmux_apply_workspace takes.
    implementation(project(":libtmux-workspace"))

    // The SDK logs through SLF4J. A launcher speaking a protocol on stdout should not greet its
    // client with warnings about missing logging providers on stderr either.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(project(":libtmux-junit5"))
}

tasks.jar { manifest { attributes("Automatic-Module-Name" to "io.github.libtmux.mcp") } }