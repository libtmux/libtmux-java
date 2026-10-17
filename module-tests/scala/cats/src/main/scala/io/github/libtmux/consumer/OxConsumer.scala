package io.github.libtmux.consumer

import io.github.libtmux.ServerConfig
import io.github.libtmux.scaladsl.*
import io.github.libtmux.scaladsl.live.LiveView
import io.github.libtmux.scaladsl.ox.Flows
import io.github.libtmux.exception.UnsupportedFeatureException
import scala.util.{Failure, Success, Try, Using}

/** Ox is direct-style: a session's live view as a `Flow`, read once. Its own
  * file, because the direct-style and Cats facades name their extensions alike.
  */
object OxConsumer {
  def readOneView(config: ServerConfig, name: String): Unit =
    Using.resource(Server.open(config)) { server =>
      val session = server.session(name).getOrElse(throw new AssertionError(s"no session $name"))
      // A live view is managed control, which tmux older than 3.7 gets refused before it attaches.
      Try(Using.resource(LiveView.attach(session)) { live =>
        Flows.liveView(live).take(1).runToList()
      }) match {
        case Success(views) => assert(views.size == 1)
        case Failure(_: UnsupportedFeatureException) => ()
        case Failure(other) => throw other
      }
      server.asJava.killServer()
    }
}
