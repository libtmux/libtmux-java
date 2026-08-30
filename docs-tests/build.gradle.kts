// Compiles the code in the documentation. Never published.
//
// A snippet is the part of a project people copy and the part nothing compiles, so it goes stale
// without anything saying so. This module puts every Java fence in the READMEs and guides through
// javac against the real artifacts, so a snippet that stopped working fails the build.
//
// Depends on every published module because the documentation does.
plugins {
    id("libtmux.java-library")
    id("libtmux.tmux-matrix")
}

dependencies {
    testImplementation(project(":libtmux"))
    testImplementation(project(":libtmux-jackson"))
    testImplementation(project(":libtmux-junit5"))
    testImplementation(project(":libtmux-mcp"))
    testImplementation(project(":libtmux-workspace"))
}

// The snippets are compiled against the compile classpath, which the compiler has to be told about
// explicitly: it runs in-process and does not inherit Gradle's. Compile rather than runtime because
// that is what a consumer gets from a published POM — api dependencies and nothing more — so a
// snippet needing an implementation dependency to compile fails here rather than for a reader.
// Running one still uses the test JVM's classpath, which is what a consumer's runtime has.
//
// Every document this reads is an input. Without that, editing a README leaves the task up to date
// and the check silently stops happening — which was true here until a deliberately broken snippet
// failed to fail.
tasks.withType<Test>().configureEach {
    val classpath = sourceSets.test.get().compileClasspath
    val root = rootProject.layout.projectDirectory
    val documents =
        rootProject.fileTree(root) {
            include("README.md", "*/README.md", "docs/guide/*.md", "docs/parity/*.md")
        }

    inputs.files(classpath)
    inputs.files(documents).withPropertyName("documentation").withPathSensitivity(PathSensitivity.RELATIVE)

    doFirst { systemProperty("libtmux.docs.classpath", classpath.asPath) }
    systemProperty("libtmux.docs.root", root.asFile.path)
}
