import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaToolchainService

plugins { id("libtmux.published-library") }

val clojureLibraries = extensions.getByType<VersionCatalogsExtension>().named("libs")
val clojureSourceSets = extensions.getByType<SourceSetContainer>()
val clojureJavaToolchains = extensions.getByType<JavaToolchainService>()
val clojureJavaVersion = providers.gradleProperty("libtmuxJavaVersion").getOrElse("21").toInt()
val clojureVersion =
    providers.gradleProperty("libtmuxClojureVersion")
        .getOrElse(clojureLibraries.findVersion("clojure").orElseThrow().requiredVersion)

clojureSourceSets.named("main") { resources.srcDir("src/main/clojure") }
clojureSourceSets.named("test") {
    resources.srcDir("src/test/clojure")
    resources.srcDir(rootProject.file("gradle/clojure-runner"))
    resources.exclude("agent/**")
}

dependencies { "api"("org.clojure:clojure:$clojureVersion") }

val namespaceGuardAsm = configurations.create("clojureNamespaceGuardAsm") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { add(namespaceGuardAsm.name, "org.ow2.asm:asm:9.9.1") }
val compileNamespaceGuard = tasks.register<JavaCompile>("compileClojureNamespaceGuard") {
    source(rootProject.fileTree("gradle/clojure-runner/agent") { include("**/*.java") })
    classpath = namespaceGuardAsm
    destinationDirectory.set(layout.buildDirectory.dir("namespace-guard/classes"))
    javaCompiler.set(clojureJavaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    options.release.set(21)
}
val namespaceGuardBootstrap = tasks.register<Jar>("clojureNamespaceGuardBootstrap") {
    from(compileNamespaceGuard.flatMap { it.destinationDirectory }) {
        include("libtmux/internal/loadguard/LoadGuard.class")
    }
    archiveFileName.set("namespace-guard-bootstrap.jar")
    destinationDirectory.set(layout.buildDirectory.dir("namespace-guard"))
}
val namespaceGuardAsmJar = tasks.register<Jar>("clojureNamespaceGuardAsm") {
    from(provider { namespaceGuardAsm.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "module-info.class")
    }
    archiveFileName.set("namespace-guard-asm.jar")
    destinationDirectory.set(layout.buildDirectory.dir("namespace-guard"))
}
val namespaceGuardAgent = tasks.register<Jar>("clojureNamespaceGuardAgent") {
    from(compileNamespaceGuard.flatMap { it.destinationDirectory }) {
        exclude("libtmux/internal/loadguard/LoadGuard.class")
    }
    manifest.attributes(
        "Premain-Class" to "libtmux.internal.loadguard.NamespaceAgent",
        "Can-Retransform-Classes" to "true",
    )
    archiveFileName.set("namespace-guard-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("namespace-guard"))
}

val declaredClojureTests = provider { extra["clojureTestNamespaces"] as String }
val discoveredClojureTests = provider {
    fileTree("src/test/clojure") { include("**/*_test.clj") }
        .files
        .map { source ->
            source.relativeTo(file("src/test/clojure"))
                .invariantSeparatorsPath
                .removeSuffix(".clj")
                .replace('_', '-')
                .replace('/', '.')
        }
        .sorted()
}

val clojureNamespaceLoad = tasks.register<JavaExec>("clojureNamespaceLoad") {
    group = "verification"
    description = "Loads public Clojure namespaces without reflection warnings or resource creation."
    dependsOn(tasks.named("testClasses"), namespaceGuardAgent, namespaceGuardBootstrap, namespaceGuardAsmJar)
    classpath = clojureSourceSets.named("test").get().runtimeClasspath
    mainClass.set("clojure.main")
    args("-m", "libtmux.internal.test-runner", "load")
    jvmArgs("-Dclojure.main.report=stderr", "-Xshare:off")
    javaLauncher.set(clojureJavaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(clojureJavaVersion))
    })
    doFirst {
        jvmArgs("-javaagent:${namespaceGuardAgent.get().archiveFile.get().asFile}=" +
            namespaceGuardBootstrap.get().archiveFile.get().asFile + File.pathSeparator +
            namespaceGuardAsmJar.get().archiveFile.get().asFile)
    }
}

val clojureNamespaceGuardTest = tasks.register<JavaExec>("clojureNamespaceGuardTest") {
    group = "verification"
    description = "Proves namespace loading rejects executor, process and socket creation."
    dependsOn(tasks.named("testClasses"), namespaceGuardAgent, namespaceGuardBootstrap, namespaceGuardAsmJar)
    classpath = clojureSourceSets.named("test").get().runtimeClasspath
    mainClass.set("clojure.main")
    args("-m", "libtmux.internal.test-runner", "guard-probes")
    jvmArgs("-Xshare:off")
    javaLauncher.set(clojureJavaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(clojureJavaVersion))
    })
    doFirst {
        jvmArgs("-javaagent:${namespaceGuardAgent.get().archiveFile.get().asFile}=" +
            namespaceGuardBootstrap.get().archiveFile.get().asFile + File.pathSeparator +
            namespaceGuardAsmJar.get().archiveFile.get().asFile)
    }
}

val clojureTest = tasks.register<JavaExec>("clojureTest") {
    group = "verification"
    description = "Runs the module's native Clojure unit tests."
    dependsOn(tasks.named("testClasses"))
    classpath = clojureSourceSets.named("test").get().runtimeClasspath
    mainClass.set("clojure.main")
    args("-m", "libtmux.internal.test-runner", "test")
    jvmArgs("-Dclojure.main.report=stderr")
    javaLauncher.set(clojureJavaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(clojureJavaVersion))
    })
    doFirst {
        systemProperty("libtmux.clojure.test-namespaces", declaredClojureTests.get())
    }
}

val clojureTestInventory = tasks.register("clojureTestInventory") {
    group = "verification"
    description = "Fails when a native Clojure test namespace is omitted from the runner."
    inputs.files(fileTree("src/test/clojure") { include("**/*_test.clj") })
    doLast {
        val declared = declaredClojureTests.get().split(',').sorted()
        require(declared == discoveredClojureTests.get()) {
            "configured Clojure tests $declared do not match source tests ${discoveredClojureTests.get()}"
        }
    }
}

val prepareClojureTestClasspath = tasks.register("prepareClojureTestClasspath") {
    group = "build setup"
    description = "Writes the reusable native Clojure test classpath."
    dependsOn(tasks.named("testClasses"))
    val destination = layout.buildDirectory.file("clojure/test-classpath.txt")
    inputs.files(clojureSourceSets.named("test").map { it.runtimeClasspath })
    outputs.file(destination)
    doLast {
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(clojureSourceSets.named("test").get().runtimeClasspath.asPath + "\n")
        }
    }
}

tasks.named("check") {
    dependsOn(clojureNamespaceLoad, clojureNamespaceGuardTest, clojureTest, clojureTestInventory)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("755") }
    filePermissions { unix("644") }
}
