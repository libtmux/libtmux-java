plugins {
    id("libtmux.java-library")
    application
}

application {
    mainClass = "io.github.libtmux.tools.mcpswap.McpSwap"
    applicationName = "mcp-swap"
}

dependencies {
    testImplementation(project(":test-support"))
    implementation(libs.jackson.databind)
    implementation(libs.tomlj)
}

tasks.withType<Javadoc>().configureEach { enabled = false }
