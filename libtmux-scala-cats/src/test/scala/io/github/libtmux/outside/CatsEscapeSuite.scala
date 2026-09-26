package io.github.libtmux.outside

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import io.github.libtmux as lib
import io.github.libtmux.{ServerConfig, ServerEndpoint}
import io.github.libtmux.scaladsl.cats.{Client, Pane, Server, Session, Window}
import java.nio.file.Path
import munit.FunSuite

/** From a package with no access to `io.github.libtmux.scaladsl`, every Cats
  * handle reaches its Java one through `.asJava`, as the direct-style handles
  * do: the facade's one escape hatch, public on every handle.
  */
final class CatsEscapeSuite extends FunSuite {

  // Compiled, never called: each line fails to compile if its `.asJava` is not
  // public, and names the Java type it must answer.
  def escapes(
      session: Session[IO],
      window: Window[IO],
      pane: Pane[IO],
      client: Client[IO]
  ): (lib.Session, lib.Window, lib.Pane, lib.Client) =
    (session.asJava, window.asJava, pane.asJava, client.asJava)

  test("a Cats server answers the Java client it wraps") {
    // `true` stands in for tmux: nothing here reaches a server.
    val config = ServerConfig
      .builder()
      .binary("true")
      .endpoint(
        ServerEndpoint.socketPath(
          Path.of("/tmp/libtmux-java-test/cats-escape")
        )
      )
      .build()
    val client = lib.Server.open(config)
    try
      Server
        .fromJava[IO](client)
        .use(server => IO(assert(server.asJava eq client)))
        .unsafeRunSync()
    finally client.close()
  }
}
