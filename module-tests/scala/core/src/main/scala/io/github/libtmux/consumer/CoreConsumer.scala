package io.github.libtmux.consumer

import io.github.libtmux.{ServerConfig, ServerEndpoint, SessionSpec, SplitSpec}
import io.github.libtmux.scaladsl.*
import io.github.libtmux.scaladsl.query.*
import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.util.Using

/** Drives a real tmux through the staged direct-style facade, and fails if the
  * core artifact brought Cats Effect, FS2 or Ox onto the runtime classpath.
  */
object CoreConsumer {
  def main(arguments: Array[String]): Unit = {
    for (optional <- Seq("cats.effect.IO", "fs2.Stream", "ox.flow.Flow"))
      assert(
        scala.util.Try(Class.forName(optional, false, getClass.getClassLoader)).isFailure,
        s"libtmux-scala_3 brought $optional onto the runtime classpath"
      )
    val root = Files.createDirectories(Path.of("/tmp/libtmux-java-test"))
    val directory = Files.createTempDirectory(root, "consumer-scala-")
    val config = ServerConfig
      .builder()
      .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
      .build()
    try
      Using.resource(Server.open(config)) { server =>
        val session = server.newSession(
          SessionSpec.builder().named("consumer-core").running("cat").build()
        )
        val window = session.windows.head
        val second = window.split(SplitSpec.builder().running("cat").build())
        val panes = window.refresh().panes
        assert(panes.size == 2, "a split must leave two panes")
        panes.matching(Pane.id.is(second.info.id.value())).exactlyOne match {
          case Right(pane) => assert(pane == second)
          case Left(error) => throw new AssertionError(error.toString)
        }
        assert(server.session("consumer-core").isDefined)
        server.asJava.killServer()
      }
    finally
      Using.resource(Files.walk(directory)) { paths =>
        paths.sorted(Comparator.reverseOrder()).forEach(Files.delete)
      }
    println("staged scala consumer ran through libtmux-scala_3")
  }
}
