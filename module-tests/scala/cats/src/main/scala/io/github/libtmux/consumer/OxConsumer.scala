package io.github.libtmux.consumer

import io.github.libtmux.ServerConfig
import io.github.libtmux.scaladsl.*
import io.github.libtmux.scaladsl.live.LiveView
import io.github.libtmux.scaladsl.ox.Flows
import scala.util.Using

/** Ox is direct-style: a session's live view as a `Flow`, read once. Its own
  * file, because the direct-style and Cats facades name their extensions alike.
  */
object OxConsumer {
  def readOneView(config: ServerConfig, name: String): Unit =
    Using.resource(Server.open(config)) { server =>
      val session = server.session(name).getOrElse(throw new AssertionError(s"no session $name"))
      val views = Using.resource(LiveView.attach(session)) { live =>
        Flows.liveView(live).take(1).runToList()
      }
      assert(views.size == 1)
      server.asJava.killServer()
    }
}
