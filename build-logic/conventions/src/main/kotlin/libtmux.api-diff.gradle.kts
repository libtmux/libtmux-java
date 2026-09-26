// This project's API carries no compatibility guarantee (see CONTRIBUTING.md): a release may break
// callers without notice. What it promises instead is that every break is written down. This gate
// compares the module's built jar against its own last released coordinate,
// io.github.libtmux:<project.name>:<libtmuxApiBaseline>, and fails when a source- or
// binary-incompatible change touches a public type that MIGRATION.md's "## Next release" section
// does not name on its own `api-break:` line. A mention of the type in prose is not an entry.
abstract class ApiDiffExtension {
    // Packages japicmp should not compare. Empty by default — most modules have no equivalent of
    // core's io.github.libtmux.internal, a package public only because several packages here share
    // it, and hidden from an actual caller by the module descriptor rather than by Java visibility.
    abstract val excludePackages: ListProperty<String>
}

val apiDiff = extensions.create<ApiDiffExtension>("apiDiff")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val apiBaselineVersion = providers.gradleProperty("libtmuxApiBaseline")

val japicmpTool = configurations.create("japicmpTool") { isCanBeConsumed = false }
val apiBaselineJar =
    configurations.create("apiBaselineJar") {
        isCanBeConsumed = false
        isTransitive = false
    }

dependencies {
    "japicmpTool"(
        "com.github.siom79.japicmp:japicmp:${libs.findVersion("japicmp").orElseThrow().requiredVersion}:jar-with-dependencies"
    )
    apiBaselineVersion.orNull?.let { "apiBaselineJar"("io.github.libtmux:${project.name}:$it") }
}

// Lenient: an unresolvable baseline (no network, or a version not yet published) is reported by the
// gate below rather than by a hard failure here, which would also break every offline build.
val apiBaselineFiles = apiBaselineJar.incoming.artifactView { isLenient = true }.files

// The released jar, and only that: a version that does not resolve can come back as this build's
// own jar, and comparing a jar with itself passes every time.
val apiBaselineResolved =
    provider {
        val version = apiBaselineVersion.orNull
        version != null && apiBaselineFiles.files.singleOrNull()?.name == "${project.name}-$version.jar"
    }

val apiDiffReport = layout.buildDirectory.file("reports/japicmp/report.xml")

val generateApiDiffReport =
    tasks.register<JavaExec>("generateApiDiffReport") {
        group = "verification"
        description = "Runs japicmp comparing this build's jar against the last released one."
        classpath = japicmpTool
        mainClass.set("japicmp.JApiCmp")

        val newJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
        val report = apiDiffReport
        val exclude = apiDiff.excludePackages

        inputs.file(newJar)
        inputs.files(apiBaselineFiles).optional(true)
        inputs.property("excludePackages", exclude)
        outputs.file(report)

        val resolved = apiBaselineResolved
        onlyIf { resolved.get() }

        doFirst {
            report.get().asFile.parentFile.mkdirs()
            args(
                "--old",
                apiBaselineFiles.singleFile.absolutePath,
                "--new",
                newJar.get().asFile.absolutePath,
                "-a",
                "public",
            )
            val excluded = exclude.get()
            if (excluded.isNotEmpty()) {
                // The module descriptor, not Java accessibility, is what actually hides a package
                // listed here; a caller can never reach it either way.
                args("--exclude", excluded.joinToString(","))
            }
            args(
                "--ignore-missing-classes",
                "--xml-file",
                report.get().asFile.absolutePath,
            )
        }
    }

tasks.register("checkApiDiffAgainstMigrationNotes") {
    group = "verification"
    description = "Fails when a source- or binary-incompatible public API change has no api-break line."
    dependsOn(generateApiDiffReport)

    val report = apiDiffReport
    val migrationNotes = rootProject.file("MIGRATION.md")
    val baselineVersion = apiBaselineVersion
    val baselineResolvable = apiBaselineResolved
    // Offline work may skip the gate; CI may not, or an unreachable baseline reads as a pass.
    val strict = providers.environmentVariable("CI").isPresent
    inputs.file(migrationNotes)

    doLast {
        fun apiBreak(element: org.w3c.dom.Element): Boolean {
            if (element.getAttribute("sourceCompatible") == "false" ||
                element.getAttribute("binaryCompatible") == "false"
            ) {
                return true
            }
            for (tag in listOf("method", "constructor", "field")) {
                val members = element.getElementsByTagName(tag)
                for (index in 0 until members.length) {
                    val member = members.item(index) as org.w3c.dom.Element
                    if (member.getAttribute("sourceCompatible") == "false" ||
                        member.getAttribute("binaryCompatible") == "false"
                    ) {
                        return true
                    }
                }
            }
            return false
        }

        fun skip(reason: String) {
            if (strict) throw GradleException("API gate cannot run in CI: $reason")
            logger.warn("API gate skipped: $reason")
        }
        if (!baselineVersion.isPresent) {
            skip("no libtmuxApiBaseline property is set")
            return@doLast
        }
        if (!baselineResolvable.get()) {
            skip(
                "io.github.libtmux:${project.name}:${baselineVersion.get()} is not resolvable from " +
                    "the configured repositories"
            )
            return@doLast
        }

        val nextRelease = run {
            val notes = migrationNotes.readText()
            val start = notes.indexOf("## Next release")
            require(start >= 0) { "MIGRATION.md has no \"## Next release\" section" }
            val end = notes.indexOf("\n## ", start + 1).let { if (it < 0) notes.length else it }
            notes.substring(start, end)
        }

        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(report.get().asFile)
        val classes = document.getElementsByTagName("class")

        val declared = Regex("(?m)^api-break: ([A-Za-z_][A-Za-z0-9_]*)\\s*$")
            .findAll(nextRelease)
            .map { it.groupValues[1] }
            .toSet()

        val undocumented = sortedSetOf<String>()
        for (i in 0 until classes.length) {
            val element = classes.item(i) as org.w3c.dom.Element
            if (!apiBreak(element)) continue
            val fqn = element.getAttribute("fullyQualifiedName")
            // A nested class folds into its enclosing top-level class: MIGRATION.md documents a
            // break at the granularity it names things, and a caller never imports a class by its
            // binary $-name.
            val simpleName = fqn.substringAfterLast('.').substringBefore('$')
            if (simpleName !in declared) undocumented += "$fqn (as $simpleName)"
        }

        require(undocumented.isEmpty()) {
            "source- or binary-incompatible change(s) missing an `api-break:` line in " +
                "MIGRATION.md's \"## Next release\" section:\n" +
                undocumented.joinToString("\n") { "  $it" }
        }
        logger.lifecycle("every source- or binary-incompatible public API change has an api-break line")
    }
}

tasks.named("check") { dependsOn("checkApiDiffAgainstMigrationNotes") }
