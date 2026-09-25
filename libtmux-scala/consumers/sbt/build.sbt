lazy val writeRuntime = taskKey[File]("Export resolved application artifacts.")

ThisBuild / scalaVersion := sys.env("CONSUMER_SCALA_VERSION")
ThisBuild / organization := "consumer.fixture"
ThisBuild / version := "0"
ThisBuild / scalacOptions ++= Seq("-release:25", "-deprecation", "-feature", "-Werror")

lazy val consumerSettings = Seq(
  publish / skip := true,
  Test / fork := true,
  Test / parallelExecution := false,
  libraryDependencies += "io.github.libtmux" % "libtmux-junit5" %
    sys.env(if (name.value == "direct") "CONSUMER_DIRECT_JAVA_VERSION"
      else "LIBTMUX_JAVA_VERSION") % Test,
  writeRuntime := {
    val report = update.value
    def rows(paths: Set[File], configuration: String): String = {
      val resolved = report.configurations
      .filter(_.configuration.name == configuration)
      .flatMap(_.modules)
      .flatMap { report =>
        report.artifacts.collect {
          case (_, path) if paths(path) =>
            Seq(report.module.organization, report.module.name,
              report.module.revision, path.getCanonicalPath).mkString("\t")
        }
      }.distinct.sorted
      require(resolved.size == paths.size, "Artifact report is incomplete")
      resolved.mkString("", "\n", "\n")
    }
    val output = baseDirectory.value / "runtime.tsv"
    IO.write(output, rows((Runtime / externalDependencyClasspath).value.map(_.data).toSet, "runtime"))
    IO.write(baseDirectory.value / "test.tsv",
      rows((Test / externalDependencyClasspath).value.map(_.data).toSet, "test"))
    IO.write(baseDirectory.value / "compiler.txt",
      scalaInstance.value.allJars.map(_.getName).sorted.mkString("", "\n", "\n"))
    IO.write(baseDirectory.value / "build-tool.txt", sbtVersion.value + "\n")
    output
  }
)

lazy val root = project.in(file(".")).aggregate(core, cats, direct)
  .settings(publish / skip := true)
lazy val core = project.in(file("core")).settings(consumerSettings)
lazy val cats = project.in(file("cats")).settings(consumerSettings)
lazy val direct = project.in(file("direct")).settings(consumerSettings)
