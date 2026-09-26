# Queries

Acquire once, then use Scala collections. A handle's captured fields (`.info`,
returning the real Java `PaneState`/`WindowState`/`SessionState`/`ClientState`
record) describe state as of that capture. A `filter`, `find`, `collect`, sort
or comprehension over a `Vector` of handles does not acquire another
snapshot. A custom predicate can still perform whatever work its author puts
inside it.

## Native predicates

`config` identifies the server. Keep the capture while deriving several views
of the same data. A view defers local computation; it does not refresh tmux.

<!-- snippet: scala-sync: query-native -->
```scala
import io.github.libtmux.scaladsl.{config => _, *}
import scala.util.Using

Using.resource(Server.open(config)) { server =>
  val panes = server.panes()
  val named = panes.filter(_.info.title().nonEmpty)
  val paths = panes.collect {
    case pane if pane.info.pid().isPresent => pane.info.currentPath()
  }
  val firstActive = panes.find(_.info.active())
  val deferred = panes.view.filter(_.info.title().nonEmpty)
  assert(deferred.toVector == named)
  assert(paths.size <= panes.size)
  assert(firstActive.forall(_.info.active()))
}
```

Missing metadata answers through the record's own Java accessor (`Optional`,
`OptionalLong`): a missing PID is different from PID zero, and unavailable
floating-pane metadata is different from `Optional.of(false)`.

## The typed field DSL and strict cardinality

Fields hang on the handle type's own companion (`Pane.command`, `Pane.active`,
...), generated one-line forwards to Java's own field metamodel
(`Pane_.command()`). `.matching` filters a captured `Vector` locally; `&&`,
`||` and `!` compose expressions, alongside their named forms `.and`, `.or`
and `.not`. `.exactlyOne`/`.atMostOne` give the strict cardinality `headOption`
and `find` do not: both return `Either[CardinalityError, ...]`, mirroring
Java's own `CardinalityException` leaves losslessly, including
`MultipleMatches`'s `atLeast` count.

<!-- snippet: scala-sync: query-cardinality -->
```scala
import io.github.libtmux.scaladsl.query.*

assert(Vector.empty[Int].atMostOne == Right(None))
assert(Vector("selected").exactlyOne == Right("selected"))
val ambiguous = Vector(1, 2).atMostOne
assert(ambiguous == Left(CardinalityError.MultipleMatches(2)))
```

## Symbolic and named operators

<!-- snippet: scala-sync: query-java-expression -->
```scala
import io.github.libtmux.scaladsl.{config => _, *}
import io.github.libtmux.scaladsl.query._
import scala.util.Using

Using.resource(Server.open(config)) { server =>
  val panes = server.panes()
  val expression = Pane.command.is("cat") && Pane.width.atLeast(1)
  val selected = panes.matching(expression)
  val native = panes.filter(p => p.info.currentCommand() == "cat" && p.info.size().width() >= 1)
  assert(selected == native)
}
```

The Cats module reads the same expressions over a captured `Vector`, inside
the effect's own `map`: `server.panes.map(_.filter(...))` (`Vector[Pane[F]]`
does not itself carry `.matching` — apply the predicate to each pane's own
`.info` field instead, or to `.underlying.asJava` for a Java expression). This
is still local evaluation over a captured vector; it does not run a Java
command.

An invalid field/operator combination must fail at compilation:

<!-- snippet: scala-reject: query-invalid-op | contains is not a member -->
```scala
import io.github.libtmux.scaladsl.Pane
Pane.active.contains("yes")
```

## Relationships and identity

Java's `Session_`, `Window_`, `Pane_` and `Client_` expose the canonical
fields; the Scala field companions are one-line forwards to them, never a
second, hand-copied metamodel. Use their existing `any`, `all`, `none` and
to-one `is` relations, exposed through `Fields.ToManyRef`/`Fields.ToOneRef`.
`all` over an empty relation is true. Applying `any` to a conjunction requires
one related object satisfying both conditions; conjoining two separate `any`
expressions allows two different related objects.

Window equality includes its session and index placement. Pane equality is
physical, while traversal retains each occurrence's window context. A vector
can therefore contain equal pane handles reached through different links.
Deduplicate only when that is the intended question, retaining server identity
when mixing endpoints. See [ownership](ownership.md).

## Serialization and regular expressions

Opted-in serialization uses the separate Java `libtmux-jackson` artifact and
its `libtmux.filter/1` schema, over the raw Java `FilterExpr` an `Expr[T]`
wraps (`.asJava`). Keep the canonical Java field and relation objects;
rebuilding an accessor with the same name does not establish model authority.
Unknown schemas, fields, operators and relations remain errors. Arbitrary
Scala closures are not serialized.

Java regex matching uses `java.util.regex.Pattern` and substring search through
`Matcher.find`. Supply a Scala regex's `.pattern` explicitly. Neither the
expression adapter nor serialization implies tmux `-f` compilation or an MCP
query parameter.
