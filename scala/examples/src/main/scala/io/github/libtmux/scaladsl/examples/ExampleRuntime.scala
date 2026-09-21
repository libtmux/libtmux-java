package io.github.libtmux.scaladsl.examples

import io.github.libtmux.{ServerConfig, ServerEndpoint}
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import scala.jdk.CollectionConverters._

private[examples] object ExampleRuntime {
  val deadline: Duration = Duration.ofMillis(800)

  def config(arguments: Array[String]): ServerConfig = {
    require(
      arguments.length == 3,
      "expected: tmux-binary socket-path config-file"
    )
    ServerConfig
      .builder()
      .binary(arguments(0))
      .endpoint(ServerEndpoint.socketPath(Path.of(arguments(1))))
      .configFile(Path.of(arguments(2)))
      .defaultTimeout(deadline)
      .build()
  }

  def name(prefix: String): String =
    prefix + "-" + UUID.randomUUID().toString.take(8)

  def shell(config: ServerConfig, arguments: String*): String =
    (config.endpointCommand().asScala.toVector ++ arguments)
      .map(word => "'" + word.replace("'", "'\"'\"'") + "'")
      .mkString(" ")
}
