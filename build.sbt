import BuildSupport._

import com.jsuereth.sbtpgp.PgpKeys

lazy val verifyJavaStage = taskKey[Unit]("Check the selected Java Maven stage.")
lazy val verifyNamespace = taskKey[Unit]("Reject the Scala-shadowing package.")
lazy val publicationCoordinates =
  taskKey[Seq[String]]("Verify and list declared cross artifacts.")
lazy val documentationInventory =
  taskKey[File]("Record every classified Scala documentation fence.")
lazy val javaArtifactVersion =
  settingKey[String]("Exact Java dependency version.")
lazy val javaRepository = settingKey[File]("Isolated Java Maven repository.")
lazy val scalaStaging =
  settingKey[File]("Isolated Scala Maven staging directory.")
lazy val centralPortalRelease =
  settingKey[Boolean](
    "Publish signed artifacts into the Central Portal bundle."
  )
lazy val operationCatalog =
  taskKey[File](
    "The real operation-catalog.json, extracted from the staged libtmux jar."
  )
lazy val generateOperationSources =
  taskKey[Seq[File]](
    "Generate direct-style extensions or Cats forwards from operationCatalog."
  )
lazy val fieldCatalogFile =
  taskKey[File](
    "The real field-catalog.tsv, extracted from the staged libtmux jar."
  )
lazy val generateFieldSources =
  taskKey[Seq[File]](
    "Generate PaneFields/SessionFields/WindowFields/ClientFields from fieldCatalogFile."
  )
lazy val codegenSelfTest =
  taskKey[Unit](
    "Prove ScalaCodegen's mapping is correct, and that a bad catalog fails it."
  )

ThisBuild / organization := "io.github.libtmux"
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / scalafmtConfig :=
  (ThisBuild / baseDirectory).value / ".scalafmt.conf"
ThisBuild / version := configured(
  "libtmux.scala.version",
  "LIBTMUX_SCALA_VERSION"
)
  .getOrElse(repositoryVersion((ThisBuild / baseDirectory).value))
ThisBuild / javaArtifactVersion :=
  configured("libtmux.java.version", "LIBTMUX_JAVA_VERSION")
    .getOrElse(
      repositoryVersion((ThisBuild / baseDirectory).value)
    )
ThisBuild / javaRepository := configuredFile(
  "libtmux.java.repository",
  "LIBTMUX_JAVA_REPOSITORY",
  (ThisBuild / baseDirectory).value / "libtmux-scala" / "target" /
    "java-repository"
)
ThisBuild / scalaStaging := configuredFile(
  "libtmux.scala.staging",
  "LIBTMUX_SCALA_STAGING",
  (ThisBuild / baseDirectory).value / "libtmux-scala" / "target" / "staging"
)
ThisBuild / localStaging := Some(
  MavenCache(
    "libtmux Scala Central staging",
    (ThisBuild / baseDirectory).value / "libtmux-scala" / "target" /
      "sona-staging"
  )
)
ThisBuild / centralPortalRelease := {
  configured(
    "libtmux.scala.release",
    "LIBTMUX_SCALA_RELEASE"
  ) match {
    case None            => false
    case Some("central") => true
    case Some(value)     => sys.error("LIBTMUX_SCALA_RELEASE must be central")
  }
}
ThisBuild / resolvers := {
  val central = "Maven Central" at "https://repo.maven.apache.org/maven2"
  if ((ThisBuild / centralPortalRelease).value) Seq(central)
  else
    Seq(
      "libtmux Java stage" at (ThisBuild / javaRepository).value.toURI.toString,
      central
    )
}
ThisBuild / externalResolvers := (ThisBuild / resolvers).value
ThisBuild / publishTo := {
  if ((ThisBuild / centralPortalRelease).value) localStaging.value
  else Some(MavenCache("libtmux Scala stage", (ThisBuild / scalaStaging).value))
}
ThisBuild / publishMavenStyle := true
ThisBuild / packageTimestamp := Package.fixed2010Timestamp
ThisBuild / pomIncludeRepository := (_ => false)
ThisBuild / versionScheme := Some("semver-spec")
ThisBuild / homepage := Some(url("https://github.com/libtmux/libtmux-java"))
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/libtmux/libtmux-java"),
    "scm:git:https://github.com/libtmux/libtmux-java.git",
    Some("scm:git:ssh://git@github.com/libtmux/libtmux-java.git")
  )
)
ThisBuild / developers := List(
  Developer(
    "libtmux",
    "libtmux contributors",
    "",
    url("https://github.com/libtmux")
  )
)
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-release:25",
  "-language:strictEquality"
)
ThisBuild / javacOptions ++= Seq("--release", "25", "-Xlint:all", "-Werror")
ThisBuild / Test / parallelExecution := false
// The lock pins third-party dependencies. The staged libtmux Java jars are this build's own and
// change with every Java edit, so a lock over them would only ever say that.
ThisBuild / dependencyLockModuleFilter := moduleFilter(organization =
  "io.github.libtmux"
)
// Read inside makeBom's own task, which sbt's unused-key lint cannot see.
Global / excludeLintKeys += bomFileName
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

lazy val common = Seq(
  verifyJavaStage := {
    if ((ThisBuild / centralPortalRelease).value) {
      require(
        !(ThisBuild / version).value.endsWith("-SNAPSHOT") &&
          !(ThisBuild / version).value.contains("-scala-dev."),
        "A Central Scala release must use a non-SNAPSHOT Scala version"
      )
      requirePublishedJava((ThisBuild / javaArtifactVersion).value)
    } else
      requireStaged(
        (ThisBuild / javaRepository).value,
        (ThisBuild / javaArtifactVersion).value,
        Seq("libtmux")
      )
  },
  verifyNamespace := requireNamespace(
    (Compile / unmanagedSources).value ++ (Test / unmanagedSources).value
  ),
  Compile / compile := (Compile / compile)
    .dependsOn(verifyJavaStage, verifyNamespace)
    .value,
  Compile / packageDoc / packageOptions +=
    Package.FixedTimestamp(Package.fixed2010Timestamp),
  publish := publish.dependsOn(LocalRootProject / publicationCoordinates).value,
  PgpKeys.publishSigned := PgpKeys.publishSigned
    .dependsOn(LocalRootProject / publicationCoordinates)
    .value,
  publishLocal := publishLocal
    .dependsOn(LocalRootProject / publicationCoordinates)
    .value,
  libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test,
  Test / testOptions += Tests.Argument("+l")
)

/** A CycloneDX SBOM published beside each jar, under the classifier and
  * extension the Gradle modules use, so one tool reads both builds' SBOMs.
  */
lazy val sbom = Seq(
  bomFileName := s"${moduleName.value}-${version.value}-cyclonedx.json"
) ++ addArtifact(
  Def.setting(Artifact(moduleName.value, "json", "json", "cyclonedx")),
  makeBom
)

lazy val unpublished = Seq(
  publish / skip := true,
  publishLocal / skip := true,
  publishArtifact := false
)

lazy val sourceDocumentation = Seq(
  Compile / doc / scalacOptions ++= SourceDocumentation.options(
    documentedRoots.value
  ),
  Compile / doc := SourceDocumentation.complete(
    (Compile / doc).value,
    documentedRoots.value,
    (Compile / sources).value
  )
)

/** Hand-written sources, then the generated operations and fields, which ship
  * in the sources jar and so belong in the source browser too.
  */
lazy val documentedRoots = Def.setting(
  Seq(
    (Compile / scalaSource).value -> "",
    (Compile / sourceManaged).value -> "generated/"
  )
)

/** Operations generated from the Doclet's `operation-catalog.json`, read off
  * the staged `libtmux` jar. `who` selects `ScalaCodegen.combinedDirectStyle`
  * (`core`) or `ScalaCodegen.combinedCatsForwards` (`cats`). One file for the
  * whole project: Scala 3 requires same-named top-level definitions to share
  * one compilation unit, and generated operation names repeat across owners
  * (`kill`, `info`, ...).
  */
def generatedOperations(who: OperationCatalog.Catalog => String) = Seq(
  operationCatalog := {
    val json = readStagedJarEntry(
      (ThisBuild / javaRepository).value,
      (ThisBuild / javaArtifactVersion).value,
      "libtmux",
      "META-INF/io.github.libtmux/operation-catalog.json"
    )
    val file = (Compile / target).value / "operation-catalog.json"
    IO.write(file, json)
    file
  },
  generateOperationSources := {
    val catalog = OperationCatalog.parse(IO.read(operationCatalog.value))
    val outputDir = (Compile / sourceManaged).value / "operations"
    IO.createDirectory(outputDir)
    val file = outputDir / "GeneratedOperations.scala"
    IO.write(file, who(catalog))
    Seq(file)
  },
  Compile / sourceGenerators += generateOperationSources.taskValue
)

/** `core`-only: the query DSL's field companions (`Pane.id`, ...) belong to the
  * direct-style facade alone, generated from the same staged jar's
  * field-catalog.tsv the Java `Pane_`/`Session_`/`Window_`/`Client_` classes
  * come from, so the 31 fields cannot drift from Java's own list.
  */
lazy val generatedFields = Seq(
  fieldCatalogFile := {
    val tsv = readStagedJarEntry(
      (ThisBuild / javaRepository).value,
      (ThisBuild / javaArtifactVersion).value,
      "libtmux",
      "META-INF/io.github.libtmux/field-catalog.tsv"
    )
    val file = (Compile / target).value / "field-catalog.tsv"
    IO.write(file, tsv)
    file
  },
  generateFieldSources := {
    val rows = FieldCatalog.parse(IO.read(fieldCatalogFile.value))
    val outputDir = (Compile / sourceManaged).value / "fields"
    IO.createDirectory(outputDir)
    val file = outputDir / "GeneratedFields.scala"
    IO.write(file, ScalaFieldCodegen.combinedFields(rows))
    Seq(file)
  },
  Compile / sourceGenerators += generateFieldSources.taskValue
)

/** The small, hand-authored catalog `codegenSelfTest` proves `ScalaCodegen`'s
  * mapping against — see `libtmux-scala/project/fixtures/README.md`. Kept
  * separate from `operationCatalog`, which reads the real, Doclet-produced
  * catalog off the staged jar.
  */
lazy val fixtureCatalog =
  settingKey[File]("The small fixture operation-catalog.json.")

lazy val root = project
  .in(file("."))
  .aggregate(core, cats, ox, integration, examples, benchmarks)
  .settings(unpublished)
  .settings(
    name := "libtmux-scala-build",
    publicationCoordinates := {
      val result = requireDeclaredPublications(
        state.value,
        baseDirectory.value / "libtmux-scala" / "publications.txt"
      )
      val log = streams.value.log
      result.foreach(coordinate => log.info(coordinate))
      result
    }
  )

lazy val core = project
  .in(file("libtmux-scala"))
  .settings(common)
  .settings(sbom)
  .settings(sourceDocumentation)
  .settings(generatedOperations(ScalaCodegen.combinedDirectStyle))
  .settings(generatedFields)
  .settings(
    name := "libtmux-scala",
    description := "Scala collections and blocking operations over libtmux for Java.",
    libraryDependencies += organization.value % "libtmux" %
      (ThisBuild / javaArtifactVersion).value,
    fixtureCatalog := (ThisBuild / baseDirectory).value / "libtmux-scala" / "project" / "fixtures" /
      "operation-catalog.json",
    codegenSelfTest := {
      val catalog = OperationCatalog.parse(IO.read(fixtureCatalog.value))
      val byOwner = ScalaCodegen.byOwner(catalog).toMap
      val paneOps = byOwner.getOrElse(
        "io.github.libtmux.Pane",
        sys.error("codegen self-test: fixture catalog has no Pane operations")
      )
      val paneSource =
        ScalaCodegen.directStyle("io.github.libtmux.Pane", paneOps)
      require(
        paneSource.contains("def sendLine(command: String): Unit ="),
        "codegen self-test: expected a generated Pane.sendLine forward; the fixture or template drifted"
      )
      require(
        paneSource.contains("def respawn(command: String*): Unit ="),
        "codegen self-test: expected a generated varargs Pane.respawn forward; the fixture or template drifted"
      )
      val corrupted = catalog.copy(operations =
        catalog.operations
          .updated(0, catalog.operations.head.copy(kind = "BOGUS"))
      )
      val rejected =
        try {
          ScalaCodegen.byOwner(corrupted)
          false
        } catch {
          case _: IllegalArgumentException => true
        }
      require(
        rejected,
        "codegen self-test: an unknown operation kind must fail byOwner, not pass silently"
      )
      // Direct-vs-Cats parity, checked at the template level: reflecting over the compiled
      // extension methods cannot compare the two facades directly (Scala 3 extension methods are
      // not members of the receiver's class at the JVM level; they are static-shaped methods on a
      // synthetic holder, with the receiver as an explicit first parameter), so this instead proves
      // the shared template branches identically for both layers, for every kind, from one owner's
      // operations: a CAPTURED operation forwards purely on both sides, and a MUTATION operation is
      // F-wrapped on the Cats side only.
      val catsSource =
        ScalaCodegen.catsForwards("io.github.libtmux.Pane", paneOps)
      require(
        paneSource
          .contains("def info: io.github.libtmux.snapshot.PaneState =") &&
          catsSource
            .contains("def info: io.github.libtmux.snapshot.PaneState ="),
        "codegen self-test: a CAPTURED operation must forward purely (no F[_]) on both facades"
      )
      require(
        paneSource.contains("def kill(): Unit =") && catsSource
          .contains("def kill(): F[Unit] ="),
        "codegen self-test: a MUTATION operation must be F-wrapped on the Cats facade only"
      )
    },
    Compile / compile := (Compile / compile).dependsOn(codegenSelfTest).value
  )

lazy val cats = project
  .in(file("libtmux-scala-cats"))
  .dependsOn(core)
  .settings(common)
  .settings(sbom)
  .settings(sourceDocumentation)
  .settings(generatedOperations(ScalaCodegen.combinedCatsForwards))
  .settings(
    name := "libtmux-scala-cats",
    description := "Cats Effect resources and FS2 observations for libtmux.",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.7.1",
      "co.fs2" %% "fs2-core" % "3.14.0"
    )
  )

lazy val ox = project
  .in(file("libtmux-scala-ox"))
  .dependsOn(core)
  .settings(common)
  .settings(sbom)
  .settings(sourceDocumentation)
  .settings(
    name := "libtmux-scala-ox",
    description := "An Ox Flow and live view over libtmux's direct-style facade.",
    libraryDependencies += "com.softwaremill.ox" %% "core" % "1.0.8"
  )

lazy val integration = project
  .in(file("libtmux-scala/integration"))
  .dependsOn(core, cats, ox)
  .settings(common)
  .settings(unpublished)
  .settings(
    name := "libtmux-scala-integration",
    Test / fork := true,
    Test / javaOptions ++= sys.props
      .get("libtmux.scala.fixture.mutant")
      .map(value => s"-Dlibtmux.scala.fixture.mutant=$value")
      .toSeq,
    libraryDependencies ++= Seq("libtmux-junit5", "libtmux-jackson").map {
      module =>
        organization.value % module % (ThisBuild / javaArtifactVersion).value % Test
    },
    Test / compile := (Test / compile)
      .dependsOn(Def.task {
        requireStaged(
          (ThisBuild / javaRepository).value,
          (ThisBuild / javaArtifactVersion).value,
          Seq("libtmux-junit5", "libtmux-jackson")
        )
      })
      .value
  )

lazy val examples = project
  .in(file("libtmux-scala/examples"))
  .dependsOn(core, cats, integration % "test->test")
  .settings(common)
  .settings(unpublished)
  .settings(
    name := "libtmux-scala-examples",
    Test / fork := true,
    Test / javaOptions += "-Dlibtmux.scala.docs.root=" +
      (ThisBuild / baseDirectory).value.getAbsolutePath,
    Test / sourceGenerators += Def.task {
      Documentation.sources(
        (ThisBuild / baseDirectory).value,
        (Test / sourceManaged).value
      )
    }.taskValue,
    Test / resourceGenerators += Def.task {
      Documentation.resources(
        (ThisBuild / baseDirectory).value,
        (Test / resourceManaged).value,
        (Compile / discoveredMainClasses).value,
        (Compile / unmanagedSources).value
      )
    }.taskValue,
    documentationInventory := (Test / managedResources).value
      .find(_.getName == "scala-docs-inventory.tsv")
      .getOrElse(sys.error("Scala documentation inventory was not generated"))
  )

lazy val benchmarks = project
  .in(file("libtmux-scala/benchmarks"))
  .dependsOn(core, cats)
  .settings(common)
  .settings(unpublished)
  .settings(name := "libtmux-scala-benchmarks", Compile / run / fork := true)

addCommandAlias("fmt", ";scalafmtSbt;scalafmtAll")
addCommandAlias(
  "lint",
  ";scalafmtSbtCheck;scalafmtCheckAll;dependencyLockCheck;core/compile;cats/compile;ox/compile"
)
addCommandAlias("unit", ";core/test;cats/test;ox/test")
addCommandAlias("live", ";integration/test;examples/test")
addCommandAlias("docs", ";core/doc;cats/doc;ox/doc")
addCommandAlias("stage", ";core/publish;cats/publish;ox/publish")
addCommandAlias(
  "stageSigned",
  ";core/publishSigned;cats/publishSigned;ox/publishSigned"
)
