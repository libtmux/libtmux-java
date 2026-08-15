// Shared Java conventions. A module script then declares only what makes it different.
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    id("net.ltgt.errorprone")
    id("com.diffplug.spotless")
}

repositories { mavenCentral() }

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.ADOPTIUM
    }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    // JSpecify annotations are class-retention, so compile-only keeps them out of a consumer's graph.
    compileOnly(libs.findLibrary("jspecify").orElseThrow())
    testCompileOnly(libs.findLibrary("jspecify").orElseThrow())
    errorprone(libs.findLibrary("errorprone-core").orElseThrow())
    errorprone(libs.findLibrary("nullaway").orElseThrow())

    testImplementation(platform(libs.findLibrary("junit-bom").orElseThrow()))
    testImplementation(libs.findLibrary("junit-jupiter").orElseThrow())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").orElseThrow())
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        // Nullness is checked in JSpecify mode: @NullMarked packages are non-null by default.
        check("NullAway", net.ltgt.gradle.errorprone.CheckSeverity.ERROR)
        option("NullAway:JSpecifyMode", "true")
        option("NullAway:AnnotatedPackages", "com.git_pull.libtmux")
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

// Reproducible archives: no timestamps, stable entry order.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("755") }
    filePermissions { unix("644") }
}

spotless {
    java {
        palantirJavaFormat(libs.findVersion("palantir-format").orElseThrow().requiredVersion)
        removeUnusedImports()
        endWithNewline()
    }
}

tasks.withType<Test>().configureEach {
    // "fixture" marks classes that exist to be executed by a nested engine — one of them fails on
    // purpose — so no ordinary suite may discover them. Excluded here rather than per module because
    // it holds everywhere. Tags a single module owns stay in that module: repeated useJUnitPlatform
    // calls accumulate onto one options object, so a global exclude would silently cancel the
    // include in a task built to run exactly that tag.
    useJUnitPlatform { excludeTags("fixture") }

    // Quarantine every test from the developer's own tmux. A test is supposed to pass an explicit
    // -S, but nothing in the language enforces that, and a command that omits it silently addresses
    // a real server and can kill it. Two environment values decide where a bare client lands: tmux
    // resolves its default socket under TMUX_TMPDIR when it execs, and $TMUX takes precedence over
    // that for a client started inside a pane — which the Gradle daemon may well have been.
    val tmuxTmpDir = layout.buildDirectory.dir("tmux-tmpdir").get().asFile
    environment("TMUX_TMPDIR", tmuxTmpDir.absolutePath)
    environment.remove("TMUX")
    environment.remove("TMUX_PANE")

    // Where a test puts the sockets it names explicitly, which TMUX_TMPDIR above does not govern.
    // Sibling libtmux ports run on this machine at the same time and start their own tmux servers
    // under /tmp; a server of theirs left behind by an exited run holds a pty and answers to a name
    // this suite might have chosen, which turns their debris into this suite's intermittent
    // failures. So this port takes a root of its own, and everything a test creates lands under it.
    //
    // Short on purpose: a unix socket path cannot exceed about 104 bytes, which rules out the build
    // directory and is why this is not derived from one.
    val socketRoot = providers.gradleProperty("libtmuxSocketRoot").getOrElse("/tmp/libtmux-java-test")
    systemProperty("java.io.tmpdir", socketRoot)
    doFirst {
        require(socketRoot.length <= 40) {
            "libtmuxSocketRoot is $socketRoot, too long to leave room for a socket under it"
        }
        tmuxTmpDir.mkdirs()
        File(socketRoot).mkdirs()
    }

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
