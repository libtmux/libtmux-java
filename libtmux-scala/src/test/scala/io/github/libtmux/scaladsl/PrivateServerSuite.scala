package io.github.libtmux.scaladsl

import io.github.libtmux.{ServerEndpoint, ServerLaunch}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.util.Using

final class PrivateServerSuite extends FunSuite {
  private def launch(): ServerLaunch = {
    val root = Path.of("/tmp/libtmux-java-test")
    Files.createDirectories(root)
    ServerLaunch
      .builder()
      .binary(System.getProperty("libtmux.tmux", "tmux"))
      .root(Files.createTempDirectory(root, "s-"))
      .build()
  }

  test("Using.resource ends the server and removes its directory") {
    var directory: Path = null
    val name = Using.resource(Server.start(launch())) { server =>
      directory = server.config.endpoint
        .asInstanceOf[ServerEndpoint.SocketPath]
        .path
        .getParent
      assert(server.isAlive())
      server.newSession("direct").name
    }
    assertEquals(name, "direct")
    assert(!Files.exists(directory), "the server left its directory behind")
  }
}
