package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import io.github.libtmux.OptionKey
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import munit.FunSuite

/** `Server.hooks()`/`options()`/`environment()` and the rest answer wrapper
  * classes whose own operations run through this server's `Execution`, the same
  * as every other Cats operation. Proven against real tmux here;
  * `SubsystemSuite` proves the cancellation boundary itself with a fake
  * transport.
  */
final class CatsSubsystemSuite extends FunSuite {

  test("a hook set through Hooks[F] is read back through Hooks[F]") {
    OwnedTmux.use { fixture =>
      val program = Server.resource[IO](fixture.config).use { server =>
        for {
          _ <- server.hooks.set("after-new-window", "display-message hello")
          all <- server.hooks.all()
        } yield all
      }
      val hooks = program.unsafeRunSync()
      assert(
        hooks
          .get("after-new-window")
          .exists(_.contains("display-message hello")),
        hooks
      )
    }
  }

  test("Options[F].get/set read as an OptionKey's own type") {
    OwnedTmux.use { fixture =>
      val program = Server.resource[IO](fixture.config).use { server =>
        for {
          _ <- server.options.set(OptionKey.ESCAPE_TIME, Integer.valueOf(140))
          read <- server.options.get(OptionKey.ESCAPE_TIME)
        } yield read
      }
      assertEquals(program.unsafeRunSync(), Some(Integer.valueOf(140)))
    }
  }
}
