package io.github.libtmux.scaladsl

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import io.github.libtmux.{ServerConfig, ServerEndpoint, SessionId}
import io.github.libtmux.exception.UnsupportedFeatureException
import io.github.libtmux.scaladsl.cats.Control
import java.nio.file.{Files, Path}
import java.time.Duration
import munit.FunSuite
import scala.concurrent.duration._

/** Attaching a Cats control client to a real daemon below the floor is refused
  * as unsupported, promptly and twice over: a 3.0 or 3.1 daemon keeps the
  * probe's standard output open, which used to hold the second attach until its
  * timeout. Runs in the rejection lanes of the tmux matrix and is skipped
  * elsewhere.
  */
final class OldDaemonControlSuite extends FunSuite {

  private def raw(binary: String, socket: Path, args: String*): Unit = {
    val command = List(binary, "-S", socket.toString, "-f", "/dev/null") ++ args
    val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
    process.getInputStream.readAllBytes()
    assert(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), command)
    assertEquals(process.exitValue(), 0, command.toString)
  }

  test(
    "Control.attachUnfenced on a daemon below the floor is refused as unsupported"
  ) {
    val lane = sys.props.getOrElse("libtmux.tmux.expected", "")
    assume(
      sys.props.get("libtmux.tmux.rejection").contains("true"),
      "only a rejection lane has an old daemon"
    )
    val client = sys.props.getOrElse("libtmux.tmux.client", "")
    assert(client.nonEmpty, s"lane $lane needs libtmux.tmux.client")
    val daemon = sys.props.getOrElse("libtmux.tmux", "tmux")
    val root = Files.createTempDirectory(
      Path.of("/tmp/libtmux-java-test"),
      "cats-old-daemon-"
    )
    val socket = root.resolve("s")
    try {
      raw(daemon, socket, "new-session", "-d", "-s", "sentinel")
      val config = ServerConfig
        .builder()
        .binary(client)
        .endpoint(ServerEndpoint.socketPath(socket))
        .configFile(Path.of("/dev/null"))
        .defaultTimeout(Duration.ofSeconds(60))
        .build()
      for (_ <- 1 to 2) {
        val attempt = Control
          .attachUnfenced[IO](
            config,
            new SessionId("$0"),
            Duration.ofSeconds(8)
          )
          .use(_ => IO.unit)
          .attempt
          .timeout(10.seconds)
          .unsafeRunSync()
        attempt match {
          case Left(refused: UnsupportedFeatureException) =>
            assert(refused.belowFloor(), refused.getMessage)
          case other => fail(s"expected the version refusal, got $other")
        }
      }
    } finally {
      try raw(daemon, socket, "kill-server")
      catch { case _: Throwable => () }
      Files.deleteIfExists(socket)
      Files.deleteIfExists(root)
    }
  }
}
