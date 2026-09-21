resolvers := Seq("Maven Central" at "https://repo.maven.apache.org/maven2")
externalResolvers := resolvers.value

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.2")
