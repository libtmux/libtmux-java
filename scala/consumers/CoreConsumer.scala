package io.github.libtmux.scaladsl.consumer

import io.github.libtmux._
import io.github.libtmux.scaladsl.Queries
import io.github.libtmux.scaladsl.blocking.{Server => ScalaServer}
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import scala.collection.immutable.Vector
import scala.util.Using

object CoreConsumer {
  def main(arguments: Array[String]): Unit = {
    OwnedTmux.use { fixture =>
      Using.resource(ScalaServer.open(fixture.config)) { server =>
        val session = server.newSession(
          SessionSpec.builder().named("consumer-core").running("cat").build()
        )
        val window = session.windows.head
        val second = window.split(SplitSpec.builder().running("cat").build())
        val panes: Vector[io.github.libtmux.scaladsl.blocking.Pane] =
          window.refresh().panes
        assert(panes.size == 2)
        val selected = panes.filter(
          Queries.panes(Pane_.id().is(second.info.id.value()))
        )
        assert(Queries.exactlyOne(selected).info.id == second.info.id)
        val found: Option[io.github.libtmux.scaladsl.blocking.Session] =
          server.session("consumer-core")
        assert(found.exists(_.info.id == session.info.id))
        assert(server.snapshot().panes.size >= 2)
        val borrowed = ScalaServer.fromJava(server.asJava)
        assert(borrowed.asJava eq server.asJava)
        borrowed.close()
        assert(server.isAlive())
        session.kill()
      }
      assert(fixture.server.isAlive())
    }
    println("CONSUMER_PASS core cleanup=true java=" + Runtime.version().feature())
  }
}
