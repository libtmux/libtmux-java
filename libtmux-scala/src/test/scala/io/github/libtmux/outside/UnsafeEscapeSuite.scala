package io.github.libtmux.outside

import io.github.libtmux.scaladsl.CommandResult
import io.github.libtmux.scaladsl.blocking.{
  Batch,
  Channel,
  CommandChain,
  Options,
  Server
}
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
}
