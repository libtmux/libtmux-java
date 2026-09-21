package io.github.libtmux.outside

import io.github.libtmux.scaladsl.blocking.Server
import munit.FunSuite

final class UnsafeEscapeSuite extends FunSuite {
  test("unsafeJava is the public escape") {
    val server: Server = null
    val escaped = () => server.unsafeJava
    assert(escaped != null)
  }
}
