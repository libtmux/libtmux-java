package io.github.libtmux.consumer

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import fs2.Stream
import io.github.libtmux.{ServerConfig, ServerEndpoint, SessionSpec}
import io.github.libtmux.scaladsl.cats.*
import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.util.Using

/** Drives a real tmux through the staged Cats facade, then reads the session's
  * live view as an Ox `Flow` through the staged Ox module.
  */
object CatsConsumer {
  def main(arguments: Array[String]): Unit = {
    val root = Files.createDirectories(Path.of("/tmp/libtmux-java-test"))
    val directory = Files.createTempDirectory(root, "consumer-cats-")
    val config = ServerConfig
      .builder()
      .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
      .build()
    try {
      Server
        .resource[IO](config)
        .use { server =>
          for {
            session <- server.newSession(
              SessionSpec.builder().named("consumer-cats").running("cat").build()
            )
            found <- server.hasSession("consumer-cats")
            windows <- Stream
              .emits(session.windows)
              .covary[IO]
              .evalMap(_.refresh())
              .compile
              .toVector
          } yield assert(found && windows.nonEmpty && session.asJava.name() == "consumer-cats")
        }
        .unsafeRunSync()
      OxConsumer.readOneView(config, "consumer-cats")
    } finally
      Using.resource(Files.walk(directory)) { paths =>
        paths.sorted(Comparator.reverseOrder()).forEach(Files.delete)
      }
    println("staged scala consumer ran through libtmux-scala-cats_3 and libtmux-scala-ox_3")
  }
}
