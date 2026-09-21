import sbt._
import sbt.Keys._

object BuildSupport {
  def configured(property: String, environment: String): Option[String] =
    sys.props.get(property).orElse(sys.env.get(environment))

  def repositoryVersion(root: File): String = {
    val properties = new java.util.Properties
    val input = new java.io.FileInputStream(root / "gradle.properties")
    try properties.load(input)
    finally input.close()
    properties.getProperty("libtmuxVersion")
  }

  def configuredFile(
      property: String,
      environment: String,
      default: File
  ): File = {
    val selected = configured(property, environment)
      .fold(default) { value =>
        val directory = new File(value)
        require(
          directory.isAbsolute,
          s"$environment must be an absolute local directory, not a URL"
        )
        directory
      }
      .getCanonicalFile
    require(
      !selected.exists() || selected.isDirectory,
      s"$environment must select a directory"
    )
    selected
  }

  def requireStaged(
      repository: File,
      version: String,
      names: Seq[String]
  ): Unit =
    names.foreach { name =>
      val directory = repository / "io" / "github" / "libtmux" / name / version
      Seq("pom", "jar").foreach { extension =>
        val plain = directory / s"$name-$version.$extension"
        val metadata = directory / "maven-metadata.xml"
        val snapshot = if (version.endsWith("-SNAPSHOT") && metadata.isFile) {
          (scala.xml.XML.loadFile(metadata) \\ "snapshotVersion")
            .find(node =>
              (node \ "extension").text == extension &&
                (node \ "classifier").text.isEmpty
            )
            .map(node => (node \ "value").text)
        } else None
        val artifact =
          if (plain.isFile) plain
          else
            snapshot.fold(plain) { selected =>
              require(
                selected.matches("[A-Za-z0-9_.-]+"),
                "Invalid snapshot version"
              )
              directory / s"$name-$selected.$extension"
            }
        require(
          artifact.isFile,
          s"Missing staged Java artifact: $artifact. Run scripts/stage-java.sh."
        )
      }
    }

  def requirePublishedJava(version: String): Unit = {
    require(
      !version.endsWith("-SNAPSHOT") && !version.contains("-scala-dev."),
      "A Central Scala release must pin an available non-SNAPSHOT Java version"
    )
  }

  def requireNamespace(sources: Seq[File]): Unit = {
    val forbidden = "(?m)^\\s*package\\s+io\\.github\\.libtmux\\.scala\\b".r
    sources.foreach { source =>
      require(
        forbidden.findFirstIn(IO.read(source)).isEmpty,
        s"$source declares io.github.libtmux.scala, which shadows Scala imports"
      )
    }
  }

  def requireDeclaredPublications(
      state: State,
      manifest: File
  ): Vector[String] = {
    require(
      manifest.isFile,
      "Missing Scala publication declaration: " + manifest
    )
    val declared = IO.readLines(manifest).toVector
    require(
      declared.nonEmpty && declared.forall(
        _.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")
      ),
      "Scala publication declarations must be nonempty group:artifact rows"
    )
    require(
      declared.distinct.size == declared.size,
      "Duplicate Scala publication declaration"
    )
    val extracted = Project.extract(state)
    val actual = extracted.structure.allProjectRefs.toVector.flatMap {
      reference =>
        if (extracted.runTask(reference / publish / skip, state)._2)
          Vector.empty
        else {
          val module = extracted.get(reference / projectID)
          val configured = extracted.get(reference / crossScalaVersions)
          val producers =
            if (configured.nonEmpty) configured
            else Seq(extracted.get(reference / scalaVersion))
          producers
            .map { producer =>
              val artifact = CrossVersion(
                module.crossVersion,
                producer,
                CrossVersion.binaryScalaVersion(producer)
              ).fold(module.name)(_(module.name))
              s"${module.organization}:$artifact:${module.revision}"
            }
            .distinct
            .toVector
        }
    }
    val artifacts = actual.map(_.split(":", -1).take(2).mkString(":"))
    require(
      artifacts.distinct.size == artifacts.size,
      "Multiple sbt projects publish the same Scala artifact"
    )
    require(
      artifacts.toSet == declared.toSet,
      "Scala publication declaration does not match non-skipped sbt projects. " +
        "Undeclared: " + (artifacts.toSet -- declared.toSet).toVector.sorted
          .mkString(", ") + "; not published: " +
        (declared.toSet -- artifacts.toSet).toVector.sorted.mkString(", ")
    )
    actual.sorted
  }
}
