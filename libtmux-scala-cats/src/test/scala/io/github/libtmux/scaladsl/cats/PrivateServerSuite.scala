package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import io.github.libtmux.{ServerEndpoint, ServerLaunch}
import java.nio.file.{Files, Path}
import munit.FunSuite

final class PrivateServerSuite extends FunSuite {
  private def launch(): ServerLaunch = {
    val root = Path.of("/tmp/libtmux-java-test")
    Files.createDirectories(root)
    ServerLaunch
      .builder()
      .binary(System.getProperty("libtmux.tmux", "tmux"))
      .root(Files.createTempDirectory(root, "c-"))
      .build()
  }

  test("the Resource ends the server and removes its directory on release") {
    val directory = Server
      .start[IO](launch())
      .use { server =>
        IO.blocking(
          server.asJava.config.endpoint
            .asInstanceOf[ServerEndpoint.SocketPath]
            .path
            .getParent
        ).flatTap(_ => server.newSession("cats"))
      }
      .unsafeRunSync()
    assert(!Files.exists(directory), "the server left its directory behind")
  }
}
