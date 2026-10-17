// The library's real-tmux suite. Not published, and deliberately not inside any module that is:
// these tests exercise every artifact together, and a suite living in one artifact's test source
// set makes that artifact's dependencies and lifecycle answerable for how the whole library is
// tested.
//
// The Scala facades' suites run here too, as munit tests beside the JUnit ones. OwnedTmux, the Scala
// fixture over the Java one, is a test fixture so the examples and documentation modules can start
// servers the same way.
plugins {
    id("libtmux.scala-library")
    id("libtmux.tmux-matrix")
    `java-test-fixtures`
}

val integrationClojureVersion =
    providers.gradleProperty("libtmuxClojureVersion").getOrElse(libs.versions.clojure.get())

repositories {
    maven { url = uri("https://repo.clojars.org") }
}

dependencies {
    testImplementation(project(":libtmux"))
    testImplementation(project(":libtmux-jackson"))
    testImplementation(project(":libtmux-junit5"))
    testImplementation(project(":libtmux-scala"))
    testImplementation(project(":libtmux-scala-cats"))
    testImplementation(project(":libtmux-scala-ox"))
    testImplementation(project(":libtmux-clojure"))
    testImplementation(project(":libtmux-clojure-core-async"))
    testImplementation("org.clojure:clojure:$integrationClojureVersion")
    testImplementation(libs.manifold)

    testFixturesImplementation(project(":libtmux"))
    testFixturesImplementation(project(":libtmux-junit5"))
    testFixturesImplementation(libs.scala3.library)
}

// A fixture mutant (-Plibtmux.scala.fixture.mutant=omit-client-close) proves OwnedTmux notices a
// client its test left open: the suite must then fail.
providers.gradleProperty("libtmux.scala.fixture.mutant").orNull?.let { mutant ->
    tasks.withType<Test>().configureEach { systemProperty("libtmux.scala.fixture.mutant", mutant) }
}

sourceSets.test {
    resources.srcDir("src/test/clojure")
    resources.srcDir(rootProject.file("gradle/clojure-runner"))
    resources.exclude("agent/**")
}

val clojureIntegrationTest =
    tasks.register<JavaExec>("clojureIntegrationTest") {
        group = "verification"
        description = "Runs the native Clojure real-tmux integration suite."
        dependsOn(tasks.named("testClasses"))
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("clojure.main")
        args("-m", "libtmux.internal.test-runner", "test")
        systemProperty("libtmux.clojure.expected", integrationClojureVersion)
        val namespaces =
            provider {
                fileTree("src/test/clojure") { include("**/*_test.clj") }
                    .files
                    .sorted()
                    .joinToString(",") { source ->
                        source.relativeTo(file("src/test/clojure"))
                            .invariantSeparatorsPath
                            .removeSuffix(".clj")
                            .replace('_', '-')
                            .replace('/', '.')
                    }
            }
        doFirst {
            systemProperty(
                "libtmux.clojure.test-namespaces",
                providers.gradleProperty("libtmuxClojureTestNamespaces").orNull ?: namespaces.get(),
            )
        }
        jvmArgs("-Dclojure.main.report=stderr")
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(
                JavaLanguageVersion.of(
                    providers.gradleProperty("libtmuxJavaVersion").getOrElse("25").toInt(),
                ),
            )
        })
        environment.remove("TMUX")
        environment.remove("TMUX_PANE")
        environment("TMUX_TMPDIR", "/tmp/libtmux-java-test")
        systemProperty("libtmux.docs.root", rootProject.projectDir.path)
        systemProperty(
            "libtmux.tmux",
            providers.gradleProperty("libtmuxTmuxBinary").getOrElse("tmux"),
        )
        providers.gradleProperty("libtmuxTmuxExpected").orNull?.let {
            systemProperty("libtmux.tmux.expected", it)
        }
        inputs.files(
            rootProject.fileTree(rootProject.projectDir) {
                include("README.md", "*/README.md", "docs/guide/*.md")
            },
        ).withPropertyName("clojureDocumentation")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }

val prepareClojureIntegrationClasspath =
    tasks.register("prepareClojureIntegrationClasspath") {
        group = "build setup"
        description = "Writes the reusable native Clojure integration classpath."
        dependsOn(tasks.named("testClasses"))
        val destination = layout.buildDirectory.file("clojure/integration-classpath.txt")
        inputs.files(sourceSets.test.map { it.runtimeClasspath })
        outputs.file(destination)
        doLast {
            destination.get().asFile.apply {
                parentFile.mkdirs()
                writeText(sourceSets.test.get().runtimeClasspath.asPath + "\n")
            }
        }
    }

tasks.named("check") { dependsOn(clojureIntegrationTest) }

// The locale lane needs a JVM whose platform encoding is not UTF-8, which is a process-wide choice
// read before main runs. So it gets its own fork rather than a flag, and the ordinary run excludes
// it: under UTF-8 every assertion in it would pass for the wrong reason.
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("locale") } }

val localeTest =
    tasks.register<Test>("localeTest") {
        group = "verification"
        description = "Runs the real-tmux suite's locale lane under a JVM that cannot encode non-ASCII arguments."
        val tests = sourceSets.test.get()
        testClassesDirs = tests.output.classesDirs
        classpath = tests.runtimeClasspath
        useJUnitPlatform { includeTags("locale") }

        // LC_ALL outranks LANG and LC_CTYPE for both the JVM's platform encoding and tmux's own
        // guess at whether its client reads UTF-8, so one variable puts both halves in the state
        // this lane is about.
        environment("LC_ALL", "C")

        // The macOS JDK encodes arguments and file names as UTF-8 whatever the locale says, so no
        // variable can put this lane's JVM in the state it tests there.
        onlyIf("the JVM on macOS always encodes with UTF-8") {
            !System.getProperty("os.name").startsWith("Mac")
        }

        // A tag that stopped matching would reduce this fork to nothing, and an empty run reports
        // success. The lane has to prove it ran at all before its green means anything.
        val results = reports.junitXml.outputLocation
        doLast {
            val written = results.get().asFile.listFiles { file -> file.name.endsWith(".xml") }
            require(!written.isNullOrEmpty()) { "the locale lane discovered no tests, so its result is vacuous" }
        }
    }

tasks.check { dependsOn(localeTest) }

// Shared native terminal driver; test resources never enter a published artifact.
sourceSets.test { resources.srcDir(rootProject.file("gradle/test-fixtures")) }
