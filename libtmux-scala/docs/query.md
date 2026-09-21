# Queries

Acquire once, then use Scala collections. Root listings return immutable
vectors; their fields and relationships describe captured state. A `filter`,
`find`, `collect`, sort or comprehension over those values does not acquire
another snapshot. A custom predicate can still perform whatever work its
author puts inside it.

## Native predicates

`config` identifies the server. Keep the capture while deriving several views
of the same data. A view defers local computation; it does not refresh tmux.

<!-- snippet: scala-sync: query-native -->
```scala
import io.github.libtmux.scaladsl.blocking.Server
import scala.util.Using

Using.resource(Server.open(config)) { server =>
  val panes = server.panes()
  val named = panes.filter(_.info.title.nonEmpty)
  val paths = panes.collect {
    case pane if pane.info.pid.isDefined => pane.info.currentPathText
  }
  val firstActive = panes.find(_.info.active)
  val deferred = panes.view.filter(_.info.title.nonEmpty)
  assert(deferred.toVector == named)
  assert(paths.size <= panes.size)
  assert(firstActive.forall(_.info.active))
}
```

Missing metadata remains `None`. A missing PID is different from PID zero;
unavailable floating-pane metadata is different from `Some(false)`. Path text
stays text until the explicit `currentPath()` filesystem conversion.

## Strict cardinality

`headOption` and `find` select the first match. [`Queries`][queries] supplies
the stronger guarantees: `oneOrNone` rejects multiple matches, and
`exactlyOne` rejects both absence and multiple matches. These helpers preserve
Java's distinct cardinality exceptions and accept `Iterable` values. They do
not convert traversal failures into absence.

<!-- snippet: scala-sync: query-cardinality -->
```scala
import io.github.libtmux.scaladsl.Queries
import io.github.libtmux.query.Selections

assert(Queries.oneOrNone(Vector.empty[Int]).isEmpty)
assert(Queries.exactlyOne(List("selected")) == "selected")
val ambiguous = scala.util.Try(Queries.oneOrNone(Vector(1, 2)))
assert(ambiguous.failed.get.isInstanceOf[Selections.MultipleMatchesException])
```

## Java expressions

Use the existing Java metamodel when a query must remain inspectable or
serializable. The predicate adapter evaluates against the original Java
handle. It does not cast detached information into a handle or translate a
Scala function into an expression tree.

<!-- snippet: scala-sync: query-java-expression -->
```scala
import io.github.libtmux.Pane_
import io.github.libtmux.scaladsl.Queries
import io.github.libtmux.scaladsl.blocking.Server
import scala.util.Using

Using.resource(Server.open(config)) { server =>
  val panes = server.panes()
  val expression = Pane_.command().is("cat").and(Pane_.width().atLeast(1))
  val selected = panes.filter(Queries.panes(expression))
  val native = panes.filter(p =>
    p.info.currentCommand == "cat" && p.info.size.width() >= 1
  )
  assert(selected == native)
}
```

For Cats handles, use
`panes.filter(p => expression.test(p.unsafeJava))` inside the effect's
`map`. This is still local evaluation over a captured vector; it does not run
a Java command.

An invalid field/operator combination must fail at compilation:

<!-- snippet: scala-reject: query-invalid-op | contains is not a member -->
```scala
import io.github.libtmux.Pane_
Pane_.active().contains("yes")
```

## Relationships and identity

Java's `Session_`, `Window_`, `Pane_` and `Client_` expose the canonical fields.
Use their existing `any`, `all`, `none` and to-one `is` relations. `all` over
an empty relation is true. Applying `any` to a conjunction requires one
related object satisfying both conditions; conjoining two separate `any`
expressions allows two different related objects.

Ordinary handle traversal can expose relationships that the built-in query
metamodel does not declare. Do not infer a serializable `Pane_.window` field
from `pane.window` being available on a handle.

Window equality includes its session and index placement. Pane equality is
physical, while traversal retains each occurrence's window context. A vector
can therefore contain equal pane handles reached through different links.
Deduplicate only when that is the intended question, retaining server identity
when mixing endpoints. See [ownership](ownership.md).

## Serialization and regular expressions

Opted-in serialization uses the separate Java `libtmux-jackson` artifact and
its `libtmux.filter/1` schema. Keep the canonical Java field and relation
objects; rebuilding an accessor with the same name does not establish model
authority. Unknown schemas, fields, operators and relations remain errors.
Arbitrary Scala closures are not serialized.

Java regex matching uses `java.util.regex.Pattern` and substring search through
`Matcher.find`. Supply a Scala regex's `.pattern` explicitly. Neither the
expression adapter nor serialization implies tmux `-f` compilation or an MCP
query parameter.

[queries]: ../src/main/scala/io/github/libtmux/scaladsl/query.scala
