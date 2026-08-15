// Compiles the code in the documentation. Never published.
//
// A snippet is the part of a project people copy and the part nothing compiles, so it goes stale
// without anything saying so. This module puts every Java fence in the READMEs and guides through
// javac against the real artifacts, so a snippet that stopped working fails the build.
//
// Depends on every published module because the documentation does.
plugins { id("libtmux.java-library") }

dependencies {
    testImplementation(project(":libtmux"))
    testImplementation(project(":libtmux-jackson"))
    testImplementation(project(":libtmux-junit5"))
    testImplementation(project(":libtmux-mcp"))
    testImplementation(project(":libtmux-workspace"))
}

// The snippets are compiled against this module's own test classpath, which the compiler has to be
// told about explicitly: it runs in-process and does not inherit Gradle's.
//
// Every document this reads is an input. Without that, editing a README leaves the task up to date
// and the check silently stops happening — which was true here until a deliberately broken snippet
// failed to fail.
tasks.withType<Test>().configureEach {
    val classpath = sourceSets.test.get().runtimeClasspath
    val root = rootProject.layout.projectDirectory
    val documents =
        rootProject.fileTree(root) {
            include("README.md", "*/README.md", "docs/guide/*.md")
        }

    inputs.files(classpath)
    inputs.files(documents).withPropertyName("documentation").withPathSensitivity(PathSensitivity.RELATIVE)

    doFirst { systemProperty("libtmux.docs.classpath", classpath.asPath) }
    systemProperty("libtmux.docs.root", root.asFile.path)
}
