package io.github.libtmux.scaladsl

import io.github.libtmux.{Client => JavaClient, Client_, Session => JavaSession}
import io.github.libtmux.scaladsl.query.{Expr, Fields}

/** A captured attached client, opaque over the Java handle. Every operation
  * beyond equality is an extension, generated from the operation catalog or
  * handwritten where the catalog marks it `WAIT`, `STREAM` or `LIFECYCLE`.
  */
opaque type Client = JavaClient

object Client {

  private[scaladsl] def wrap(java: JavaClient): Client = java

  given CanEqual[Client, Client] = CanEqual.derived

  def name: Fields.TextField[JavaClient] = new Fields.TextField(Client_.name())
  def session: Fields.ToOneRef[JavaClient, JavaSession] =
    new Fields.ToOneRef(Client_.session())

  // Nested, not top-level: see Pane.scala's own asJava for why.
  extension (self: Client) {

    /** The underlying Java client. Opaque wrapping is free, so this never
      * copies scope or state.
      */
    def asJava: JavaClient = self
  }

  extension (clients: Vector[Client]) {

    /** The captured clients `expr` matches: a local filter over what is already
      * held.
      */
    def matching(expr: Expr[JavaClient]): Vector[Client] =
      clients.filter(client => expr.matches(client.asJava))
  }
}
