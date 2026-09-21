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

ThisBuild / organization := "io.github.libtmux"
ThisBuild / scalaVersion := "2.13.18"
ThisBuild / crossScalaVersions := Seq("2.13.18", "3.3.8")
ThisBuild / version := configured(
  "libtmux.scala.version",
  "LIBTMUX_SCALA_VERSION"
)
  .getOrElse(repositoryVersion((ThisBuild / baseDirectory).value.getParentFile))
ThisBuild / javaArtifactVersion :=
  configured("libtmux.java.version", "LIBTMUX_JAVA_VERSION")
    .getOrElse(
      repositoryVersion((ThisBuild / baseDirectory).value.getParentFile)
    )
ThisBuild / javaRepository := configuredFile(
  "libtmux.java.repository",
  "LIBTMUX_JAVA_REPOSITORY",
  (ThisBuild / baseDirectory).value / "target" / "java-repository"
)
ThisBuild / scalaStaging := configuredFile(
  "libtmux.scala.staging",
  "LIBTMUX_SCALA_STAGING",
  (ThisBuild / baseDirectory).value / "target" / "staging"
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
  "-release:21"
)
ThisBuild / javacOptions ++= Seq("--release", "21", "-Xlint:all", "-Werror")
ThisBuild / Test / parallelExecution := false
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

lazy val unpublished = Seq(
  publish / skip := true,
  publishLocal / skip := true,
  publishArtifact := false
)

lazy val sourceDocumentation = Seq(
  Compile / doc / scalacOptions ++= SourceDocumentation.options(
    scalaBinaryVersion.value,
    (Compile / scalaSource).value
  ),
  Compile / doc := SourceDocumentation.complete(
    (Compile / doc).value,
    (Compile / scalaSource).value,
    (Compile / sources).value
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core, cats, integration, examples, benchmarks)
  .settings(unpublished)
  .settings(
    name := "libtmux-scala-build",
    crossScalaVersions := Nil,
    publicationCoordinates := {
      val result = requireDeclaredPublications(
        state.value,
        baseDirectory.value / "publications.txt"
      )
      val log = streams.value.log
      result.foreach(coordinate => log.info(coordinate))
      result
    }
  )

lazy val core = project
  .in(file("core"))
  .settings(common)
  .settings(sourceDocumentation)
  .settings(
    name := "libtmux-scala",
    description := "Scala collections and blocking operations over libtmux for Java.",
    libraryDependencies += organization.value % "libtmux" %
      (ThisBuild / javaArtifactVersion).value
  )

lazy val cats = project
  .in(file("cats"))
  .dependsOn(core)
  .settings(common)
  .settings(sourceDocumentation)
  .settings(
    name := "libtmux-scala-cats",
    description := "Cats Effect resources and FS2 observations for libtmux.",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.7.1",
      "co.fs2" %% "fs2-core" % "3.14.0"
    )
  )

lazy val integration = project
  .in(file("integration"))
  .dependsOn(core, cats)
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
  .in(file("examples"))
  .dependsOn(core, cats, integration % "test->test")
  .settings(common)
  .settings(unpublished)
  .settings(
    name := "libtmux-scala-examples",
    Test / fork := true,
    Test / javaOptions += "-Dlibtmux.scala.docs.root=" +
      (ThisBuild / baseDirectory).value.getParentFile.getAbsolutePath,
    Test / sourceGenerators += Def.task {
      Documentation.sources(
        (ThisBuild / baseDirectory).value.getParentFile,
        (Test / sourceManaged).value
      )
    }.taskValue,
    Test / resourceGenerators += Def.task {
      Documentation.resources(
        (ThisBuild / baseDirectory).value.getParentFile,
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
  .in(file("benchmarks"))
  .dependsOn(core, cats)
  .settings(common)
  .settings(unpublished)
  .settings(name := "libtmux-scala-benchmarks", Compile / run / fork := true)

addCommandAlias("fmt", ";scalafmtSbt;scalafmtAll")
addCommandAlias(
  "lint",
  ";scalafmtSbtCheck;scalafmtCheckAll;+core/compile;+cats/compile"
)
addCommandAlias("unit", ";core/test;cats/test")
addCommandAlias("crossUnit", ";+core/test;+cats/test")
addCommandAlias("live", ";integration/test;examples/test")
addCommandAlias("crossLive", ";+integration/test;+examples/test")
addCommandAlias("docs", ";+core/doc;+cats/doc")
addCommandAlias("stage", ";+core/publish;+cats/publish")
addCommandAlias("stageSigned", ";+core/publishSigned;+cats/publishSigned")
