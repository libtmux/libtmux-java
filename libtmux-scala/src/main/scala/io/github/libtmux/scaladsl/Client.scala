package io.github.libtmux.scaladsl

import io.github.libtmux.{Client => JavaClient}
import io.github.libtmux.scaladsl.query.Expr

/** A captured attached client, opaque over the Java handle. Every operation
  * beyond equality is an extension, generated from the operation catalog or
  * handwritten where the catalog marks it `WAIT`, `STREAM` or `LIFECYCLE`.
  */
opaque type Client = JavaClient

object Client {

  private[scaladsl] def wrap(java: JavaClient): Client = java

  given CanEqual[Client, Client] = CanEqual.derived

  // Generated from field-catalog.tsv: see Pane.scala's own export for why this is a re-export of
  // a separate generated object rather than a same-file companion.
  export io.github.libtmux.scaladsl.generated.ClientFields.*

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
