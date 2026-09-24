package io.github.libtmux.outside

import io.github.libtmux.{Server => JavaServer, ServerConfig, ServerEndpoint}
import io.github.libtmux.scaladsl.CommandResult
import io.github.libtmux.scaladsl.blocking.{
  Batch,
  Channel,
  CommandChain,
  Options,
  Server
}
import java.nio.file.Path
import munit.FunSuite

final class UnsafeEscapeSuite extends FunSuite {
  test("unsafeJava is the public escape") {
    val server: Server = null
    val result: CommandResult = null
    val channel: Channel = null
    val batch: Batch = null
    val chain: CommandChain = null
    val options: Options = null
    val escaped = () =>
      (
        server.unsafeJava,
        result.unsafeJava,
        channel.unsafeJava,
        batch.unsafeJava,
        chain.unsafeJava,
        options.unsafeJava
      )
    assert(escaped != null)
  }

  // `true` stands in for tmux: a closed Java client refuses before it
  // starts a process, and an open one runs it and succeeds.
  private val config = ServerConfig
    .builder()
    .binary("true")
    .endpoint(
      ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-test/unsafe-escape"))
    )
    .build()

  test("closing an owned server closes the Java client it escapes to") {
    val owned = Server.open(config)
    val escaped = owned.unsafeJava
    owned.close()
    intercept[IllegalStateException](escaped.cmd("list-sessions"))
  }

  test("closing a borrowed server leaves the Java client open") {
    val java = JavaServer.open(config)
    try {
      Server.fromJava(java).close()
      assert(java.cmd("list-sessions").succeeded())
    } finally java.close()
  }
}
