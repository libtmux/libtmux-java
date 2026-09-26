// Shared Java conventions. A module script then declares only what makes it different.
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
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
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

// Sources and javadoc jars are the publisher's to make. Declaring them here too produced two tasks
// writing one path, which Gradle reports as an undeclared dependency between them rather than as the
// collision it is. Modules that are never published do not need them at all.

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
    options.release = 25
    // Recorded in module-info, so a stack trace names io.github.libtmux@<version> and a consumer can
    // tell which release it resolved. Lazy: the publication convention sets the version later, and a
    // module that is never published has none to record.
    options.javaModuleVersion = provider { project.version.toString().takeUnless { it == "unspecified" } }
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        // Nullness is checked in JSpecify mode: @NullMarked packages are non-null by default.
        check("NullAway", net.ltgt.gradle.errorprone.CheckSeverity.ERROR)
        option("NullAway:JSpecifyMode", "true")
        option("NullAway:AnnotatedPackages", "io.github.libtmux")
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

// Javadoc runs in the gate, not only in a release.
//
// It buys less than it looks like it should: -Xlint:all with -Werror already runs doclint during
// compilation, so a broken link or a @param naming no parameter fails long before this. What this
// adds is the tool itself — the standard doclet, its options, and the jar the Portal requires —
// exercised on every push rather than for the first time after a tag is pushed.
tasks.named("check") { dependsOn(tasks.withType<Javadoc>()) }

// Stated once, by the build. A version repeated in source is a version that drifts: the MCP server
// announced 0.1.0 to every client long after the build had moved on. Lazy, because the publication
// convention sets the version after this one is applied.
tasks.jar {
    manifest { attributes("Implementation-Version" to provider { project.version.toString() }) }
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

// Compiled for 25 everywhere; run on whichever JDK a lane names. A library is run on JDKs its author
// never chose, and the ones that break it break at runtime, so the newest-JDK lane has to execute the
// suites there rather than only host Gradle on it. The property travels into the JVM so a test can
// prove where it ran.
val testJdk = providers.gradleProperty("libtmux.testJdk").map(String::toInt).orElse(25)

tasks.withType<Test>().configureEach {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = testJdk.map { JavaLanguageVersion.of(it) }
        vendor = JvmVendorSpec.ADOPTIUM
    }
    systemProperty("libtmux.testJdk", testJdk.get())

    // "fixture" marks classes that exist to be executed by a nested engine — one of them fails on
    // purpose — so no ordinary suite may discover them. Excluded here rather than per module because
    // it holds everywhere. Tags a single module owns stay in that module: repeated useJUnitPlatform
    // calls accumulate onto one options object, so a global exclude would silently cancel the
    // include in a task built to run exactly that tag.
    useJUnitPlatform { excludeTags("fixture") }

    // The tmux a suite runs is one of its inputs. A path alone is not: a matrix lane rebuilt in
    // place, or an upgraded tmux on PATH, would otherwise report a result it never produced, UP-TO-DATE
    // or from the build cache. Resolved when the task is fingerprinted, after a lane has named its
    // binary.
    val path = providers.environmentVariable("PATH")
    inputs.files(providers.provider {
        val named = systemProperties["libtmux.tmux"]?.toString() ?: "tmux"
        val binary = if (named.contains(File.separatorChar)) {
            File(named)
        } else {
            path.orNull.orEmpty().split(File.pathSeparator).map { File(it, named) }.firstOrNull(File::canExecute)
        }
        listOfNotNull(binary?.takeIf(File::isFile))
    }).withPropertyName("tmuxBinary").withPathSensitivity(PathSensitivity.NONE)

    // Quarantine every test from the developer's own tmux. A test is supposed to pass an explicit
    // -S, but nothing in the language enforces that, and a command that omits it silently addresses
    // a real server and can kill it. Two environment values decide where a bare client lands: tmux
    // resolves its default socket under TMUX_TMPDIR when it execs, and $TMUX takes precedence over
    // that for a client started inside a pane — which the Gradle daemon may well have been.
    // TMUX_TMPDIR is set per invocation in doFirst below, under the same root as the named sockets.
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
        // The quarantine shares the configured root, so overriding libtmuxSocketRoot moves the
        // bare-client sockets along with the named ones instead of splitting them across two roots.
        // Owner identity separates concurrent invocations; 16 hex digits leave AF_UNIX room.
        val quarantineIdentity = listOf(
            rootProject.rootDir.canonicalPath,
            path,
            ProcessHandle.current().pid().toString(),
        ).joinToString("\u0000")
        val quarantineDigest = MessageDigest.getInstance("SHA-256")
            .digest(quarantineIdentity.toByteArray(StandardCharsets.UTF_8))
        val quarantineName = HexFormat.of().formatHex(quarantineDigest, 0, 8)
        val tmuxTmpDir = Path.of(socketRoot, quarantineName)

        if (Files.exists(tmuxTmpDir, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(tmuxTmpDir, LinkOption.NOFOLLOW_LINKS)) {
                "tmux quarantine is not a directory: $tmuxTmpDir"
            }
            val entries = Files.walk(tmuxTmpDir).use { paths -> paths.toList() }
            val stale = entries.firstOrNull {
                it != tmuxTmpDir && !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)
            }
            require(stale == null) {
                "tmux quarantine contains a stale entry: $stale"
            }
            entries.asReversed().filter { it != tmuxTmpDir }.forEach(Files::delete)
        }
        Files.createDirectories(tmuxTmpDir)
        environment("TMUX_TMPDIR", tmuxTmpDir.toString())
        File(socketRoot).mkdirs()
    }

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
