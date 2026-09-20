package io.github.libtmux.scaladsl.consumer

import io.github.libtmux.{Pane_, Server}
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Using

object DirectConsumer {
  def main(arguments: Array[String]): Unit = {
    OwnedTmux.use { fixture =>
      Using.resource(Server.open(fixture.config)) { server =>
        val panes = server.panes().asScala.toVector
        val expression = Pane_.command().startsWith("cat")
        val selected = panes.filter(expression.test)
        val missing = server.session("scala-direct-missing").toScala
        assert(panes.nonEmpty)
        assert(panes.filter(Pane_.id().is(panes.head.id().value()).test).size == 1)
        assert(selected.forall(_.currentCommand().startsWith("cat")))
        assert(missing.isEmpty)
      }
      assert(fixture.server.isAlive())
    }
    println("CONSUMER_PASS direct cleanup=true java=" + Runtime.version().feature())
  }
}
