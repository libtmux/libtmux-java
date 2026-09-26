// Resolves the published artifacts as an sbt user does: through `%%`, from the staging repository
// the root build writes, with the install lines the documentation shows. DocumentationFactsTest keeps
// those lines and these identical, apart from the version.
val libtmuxVersion =
  sys.props.getOrElse("libtmux.version", sys.error("pass -Dlibtmux.version=<the staged version>"))
val staging = file(sys.props.getOrElse("libtmux.staging", "../../build/staging-repository"))

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / scalacOptions ++= Seq("-release:25", "-deprecation", "-feature", "-Werror")
ThisBuild / resolvers += "libtmux staging" at staging.getAbsoluteFile.toURI.toString

lazy val core = project.settings(
  libraryDependencies += "io.github.libtmux" %% "libtmux-scala" % libtmuxVersion
)

lazy val cats = project.settings(
  libraryDependencies += "io.github.libtmux" %% "libtmux-scala-cats" % libtmuxVersion
)

lazy val ox = project.settings(
  libraryDependencies += "io.github.libtmux" %% "libtmux-scala-ox" % libtmuxVersion
)

lazy val direct = project.settings(
  libraryDependencies += "io.github.libtmux" % "libtmux" % libtmuxVersion
)
