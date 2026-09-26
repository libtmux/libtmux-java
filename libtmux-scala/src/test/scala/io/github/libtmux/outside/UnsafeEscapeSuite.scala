package io.github.libtmux.outside

import io.github.libtmux.{Server => JavaServer, ServerConfig, ServerEndpoint}
import io.github.libtmux.scaladsl.Server
import java.nio.file.Path
import munit.FunSuite

/** Proves the opaque direct-style facade is fully usable from a package with no
  * special access to `io.github.libtmux.scaladsl`: the handles are the Java
  * ones under opaque types, so `.asJava` and `Server.fromJava` are ordinary
  * public API rather than a way out of a private wrapper.
  */
final class UnsafeEscapeSuite extends FunSuite {

  // `true` stands in for tmux: a closed Java client refuses before it
  // starts a process, and an open one runs it and succeeds.
  private val config = ServerConfig
    .builder()
    .binary("true")
    .endpoint(
      ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-test/unsafe-escape"))
    )
    .build()

  test("closing an owned server closes the Java client it opened") {
    val owned = Server.open(config)
    val escaped = owned.asJava
    owned.close()
    intercept[IllegalStateException](escaped.cmd("list-sessions"))
  }

  test("closing a borrowed server closes the Java client it borrowed too") {
    // Server.fromJava is an opaque identity, not a separate wrapper that could make close a
    // no-op for a borrowed handle: see Server.fromJava's own docstring.
    val java = JavaServer.open(config)
    Server.fromJava(java).close()
    intercept[IllegalStateException](java.cmd("list-sessions"))
  }
}
