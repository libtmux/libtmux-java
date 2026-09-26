import OperationCatalog._

/** Turns parsed `operation-catalog.json` operations into Scala 3 source, by
  * string template, per the coordinator's codegen ruling: generate the
  * direct-style extension methods and the Cats per-operation forwards;
  * everything the catalog marks `WAIT`, `STREAM` or `LIFECYCLE` is handwritten
  * instead (bespoke cancellation or resource scoping), so this only ever emits
  * `CAPTURED`, `READ` and `MUTATION` operations.
  *
  * The type mapping below covers exactly what the fixture catalog
  * (`libtmux-scala/project/fixtures/operation-catalog.json`) uses today:
  * primitives, `String`, `Duration`, `Optional`/`OptionalInt`, `List`, the five
  * wrapped handles (`Server`, `Session`, `Window`, `Pane`, `Client`), varargs
  * `String...`, and `FilterExpr`. Anything else passes through by its canonical
  * name, unconverted — this design's own rule for a plain Java value type with
  * no member worth hiding (`WindowId`, `Dimensions`, `ServerSnapshot`, the
  * `*State` records, ...). `CommandChain` and `Batch` are handwritten in both
  * layers instead of generated: their builder replay needs the judgment ruling
  * 4 reserves for handwritten code, not a catalog-driven template.
  */
object ScalaCodegen {

  sealed trait Target
  case object Direct extends Target
  case object CatsStyle extends Target

  private val wrappedHandles: Set[String] = Set(
    "io.github.libtmux.Server",
    "io.github.libtmux.Session",
    "io.github.libtmux.Window",
    "io.github.libtmux.Pane",
    "io.github.libtmux.Client"
  )

  private def simpleNameOf(fqn: String): String =
    fqn.substring(fqn.lastIndexOf('.') + 1)

  private def decapitalize(name: String): String =
    if (name.isEmpty) name else name.charAt(0).toLower + name.substring(1)

  /** The catalog kinds this generator ever emits. `WAIT`/`STREAM`/`LIFECYCLE`
    * operations are handwritten, per the codegen ruling.
    */
  val generatableKinds: Set[String] = Set("CAPTURED", "READ", "MUTATION")

  private def primitiveScala(name: String): String = name match {
    case "int"     => "Int"
    case "long"    => "Long"
    case "short"   => "Short"
    case "byte"    => "Byte"
    case "char"    => "Char"
    case "boolean" => "Boolean"
    case "float"   => "Float"
    case "double"  => "Double"
    case "void"    => "Unit"
    case other     => sys.error(s"unknown primitive '$other'")
  }

  /** `scalaType`: the Scala type a generated signature spells for `t`, in
    * `target`'s layer. `toScala`/`toJava`: how to convert one value across the
    * boundary, given the expression that produces it. `owner` is the enclosing
    * operation's owner FQN, needed only because a Cats wrap goes through `self`
    * (when `self` itself is the `Server[F]`) or `self.server` otherwise.
    */
  final case class Mapped(
      scalaType: String,
      toScala: String => String,
      toJava: String => String
  )

  def mapType(t: JType, target: Target, owner: String): Mapped = t match {
    case Primitive(name) =>
      Mapped(primitiveScala(name), identity, identity)

    case Named("java.lang.String", _) =>
      Mapped("String", identity, identity)

    case Named("java.time.Duration", _) =>
      Mapped(
        "_root_.scala.concurrent.duration.FiniteDuration",
        j =>
          s"_root_.scala.concurrent.duration.Duration.fromNanos($j.toNanos).asInstanceOf[_root_.scala.concurrent.duration.FiniteDuration]",
        s => s"_root_.scala.jdk.DurationConverters.ScalaDurationOps($s).toJava"
      )

    case Named("java.util.OptionalInt", _) =>
      Mapped(
        "Option[Int]",
        j => s"_root_.scala.jdk.OptionConverters.RichOptionalInt($j).toScala",
        s => s"_root_.scala.jdk.OptionConverters.RichOption($s).toJavaPrimitive"
      )

    case Named("java.util.OptionalLong", _) =>
      Mapped(
        "Option[Long]",
        j => s"_root_.scala.jdk.OptionConverters.RichOptionalLong($j).toScala",
        s => s"_root_.scala.jdk.OptionConverters.RichOption($s).toJavaPrimitive"
      )

    // java.lang.Boolean, not scala.Boolean: the wire type is a Java reference type, but every
    // Scala-facing signature spells it Boolean, matching the primitive; .booleanValue()/valueOf
    // convert explicitly rather than leaning on Scala's own boxing inference to find it.
    case Named("java.lang.Boolean", _) =>
      Mapped(
        "Boolean",
        j => s"$j.booleanValue()",
        s => s"_root_.java.lang.Boolean.valueOf($s)"
      )

    case Named("java.lang.Runnable", _) =>
      Mapped(
        "() => Unit",
        j => s"() => $j.run()",
        // A fresh lambda literal, not the bare value: Scala's SAM conversion to Runnable applies
        // to a literal at the argument position, not to an already-built () => Unit value.
        s => s"(() => $s())"
      )

    case Named("java.util.Optional", Vector(inner)) =>
      val m = mapType(inner, target, owner)
      val passthrough = isPassthrough(inner)
      Mapped(
        s"Option[${m.scalaType}]",
        j =>
          if (passthrough)
            s"_root_.scala.jdk.OptionConverters.RichOptional($j).toScala"
          else
            s"_root_.scala.jdk.OptionConverters.RichOptional($j).toScala.map(v => ${m.toScala("v")})",
        s =>
          if (passthrough)
            s"_root_.scala.jdk.OptionConverters.RichOption($s).toJava"
          else
            s"_root_.scala.jdk.OptionConverters.RichOption($s.map(v => ${m.toJava("v")})).toJava"
      )

    case Named("java.util.List", Vector(inner)) =>
      val m = mapType(inner, target, owner)
      val passthrough = isPassthrough(inner)
      Mapped(
        s"Vector[${m.scalaType}]",
        j =>
          if (passthrough)
            s"_root_.scala.jdk.CollectionConverters.ListHasAsScala($j).asScala.toVector"
          else
            s"_root_.scala.jdk.CollectionConverters.ListHasAsScala($j).asScala.iterator.map(v => ${m.toScala("v")}).toVector",
        s =>
          if (passthrough)
            s"_root_.scala.jdk.CollectionConverters.SeqHasAsJava($s).asJava"
          else
            s"_root_.scala.jdk.CollectionConverters.SeqHasAsJava($s.map(v => ${m.toJava("v")})).asJava"
      )

    case Named("java.util.Map", Vector(key, value)) =>
      // Every map key in this catalog (String, PaneId) is already passthrough; only the value ever
      // needs a per-entry conversion (Server.paneFields nests a second Map as its value).
      val keyMapped = mapType(key, target, owner)
      val valueMapped = mapType(value, target, owner)
      val valuePassthrough = isPassthrough(value)
      Mapped(
        s"_root_.scala.collection.immutable.VectorMap[${keyMapped.scalaType}, ${valueMapped.scalaType}]",
        j =>
          if (valuePassthrough)
            s"_root_.scala.collection.immutable.VectorMap.from(_root_.scala.jdk.CollectionConverters.MapHasAsScala($j).asScala)"
          else
            s"_root_.scala.collection.immutable.VectorMap.from(_root_.scala.jdk.CollectionConverters.MapHasAsScala($j).asScala.map { case (k, v) => k -> ${valueMapped.toScala("v")} })",
        s =>
          if (valuePassthrough)
            s"_root_.scala.jdk.CollectionConverters.MapHasAsJava($s).asJava"
          else
            s"_root_.scala.jdk.CollectionConverters.MapHasAsJava($s.map { case (k, v) => k -> ${valueMapped.toJava("v")} }).asJava"
      )

    case Named("java.util.function.Consumer", Vector(inner)) =>
      // A builder-consumer parameter, e.g. Consumer<SplitSpec.Builder>: the fluent overload
      // (`pane.split(s => s.toRight().percent(30))`). The builder type itself is reused verbatim
      // (a mutable Java builder has no member worth hiding), so only the Consumer wrapper changes
      // shape; a Scala T => Unit value converts to Consumer[T] via SAM conversion at the call site.
      val builderFqn = fqnOf(inner)
      Mapped(
        s"$builderFqn => Unit",
        identity,
        // A fresh lambda literal: see java.lang.Runnable's own mapping, same reason.
        s => s"((it: $builderFqn) => $s(it))"
      )

    case Named("io.github.libtmux.query.FilterExpr", Vector(inner)) =>
      val rawJava = fqnOf(inner)
      Mapped(
        s"io.github.libtmux.scaladsl.query.Expr[$rawJava]",
        j => s"io.github.libtmux.scaladsl.query.Expr($j)",
        s => s"$s.asJava"
      )

    case Named(fqn, _) if wrappedHandles.contains(fqn) =>
      val simple = simpleNameOf(fqn)
      target match {
        case Direct =>
          Mapped(simple, j => s"$simple.wrap($j)", s => s"$s.asJava")
        case CatsStyle
            if simple == "Server" && owner != "io.github.libtmux.Server" =>
          // Every non-Server handle already carries the Server[F] scope it was captured through
          // (its own `server` field); Java's own accessor (Session.server(), ...) answers exactly
          // that object, so this reuses the held reference rather than wrapping a second one, and
          // never even needs the underlying Java call.
          Mapped(s"Server[F]", _ => "self.server", s => s"$s.underlying.asJava")
        case CatsStyle =>
          val receiver =
            if (owner == "io.github.libtmux.Server") "self" else "self.server"
          Mapped(
            s"$simple[F]",
            // direct.$simple, qualified: an unqualified $simple in this package would resolve to
            // this Cats module's own same-named class, which has no wrap.
            j =>
              s"$receiver.${decapitalize(simple)}(io.github.libtmux.scaladsl.$simple.wrap($j))",
            s => s"$s.underlying.asJava"
          )
      }

    case Named(fqn, args) if args.isEmpty =>
      Mapped(fqn, identity, identity)

    case other =>
      sys.error(
        s"ScalaCodegen has no mapping for '$other'; extend mapType or keep it out of the fixture"
      )
  }

  /** Whether `t` needs no per-element conversion inside a container
    * (`Optional`/`List`).
    */
  private def isPassthrough(t: JType): Boolean = t match {
    case Named("java.lang.Boolean", _) => false
    case Named(fqn, args)              =>
      args.isEmpty && !wrappedHandles.contains(
        fqn
      ) && fqn != "io.github.libtmux.query.FilterExpr"
    case Primitive(_) => true
    case ArrayOf(_)   => false
  }

  private def fqnOf(t: JType): String = t match {
    case Named(fqn, _) => fqn
    case other         => sys.error(s"expected a named type, found $other")
  }

  private def requireWrappable(owner: String): String = {
    require(
      wrappedHandles.contains(owner),
      s"'$owner' is not a wrappable handle; add it to ScalaCodegen.wrappedHandles"
    )
    simpleNameOf(owner)
  }

  /** One parameter's generated signature fragment and its call-site Java
    * argument expression.
    */
  private def parameter(
      p: Parameter,
      isLast: Boolean,
      varargs: Boolean,
      target: Target,
      owner: String
  ): (String, String) =
    if (isLast && varargs) (s"${p.name}: String*", s"${p.name}*")
    else {
      val m = mapType(p.tpe.tpe, target, owner)
      (s"${p.name}: ${m.scalaType}", m.toJava(p.name))
    }

  private def signature(
      op: Operation,
      target: Target
  ): (String, String, String) = {
    val params = op.parameters.zipWithIndex.map { case (p, index) =>
      parameter(
        p,
        isLast = index == op.parameters.size - 1,
        varargs = op.varargs,
        target = target,
        owner = op.owner
      )
    }
    (
      params.map(_._1).mkString(", "),
      params.map(_._2).mkString(", "),
      mapType(op.returns.tpe, target, op.owner).scalaType
    )
  }

  private def scaladoc(indent: String)(op: Operation): String =
    if (op.javadoc.summary.isEmpty) ""
    else s"$indent/** ${op.javadoc.summary} */\n"

  /** A pure, zero-argument operation reads as a property (`def windows:
    * Vector[Window]`); one that reaches tmux keeps its parens even with no
    * arguments, by the ordinary Scala convention for a side-effecting nullary
    * call.
    */
  private def parenthesized(op: Operation, params: String): String =
    if (params.isEmpty && op.kind == "CAPTURED") "" else s"($params)"

  /** Direct-style extensions for one owner:
    * `extension (self: Pane) def ... = self.asJava.op(...)`.
    */
  def directStyle(owner: String, operations: Vector[Operation]): String = {
    val simple = requireWrappable(owner)
    val body = operations
      .map { op =>
        val (params, javaArgs, returnType) = signature(op, Direct)
        val returnMapped = mapType(op.returns.tpe, Direct, owner)
        val call = s"self.asJava.${op.name}($javaArgs)"
        val converted =
          if (returnType == "Unit") call else returnMapped.toScala(call)
        s"${scaladoc("    ")(op)}    def ${op.name}${parenthesized(op, params)}: $returnType =\n      $converted"
      }
      .mkString("\n\n")
    s"""  extension (self: $simple) {
       |
       |$body
       |  }""".stripMargin
  }

  /** Cats forwards for one owner:
    * `extension [F[_]](self: Pane[F]) def ... = self.server.execution(...)`.
    * `CAPTURED` operations forward purely, with no `F[_]`; `READ`/`MUTATION`
    * route through `Execution[F]`.
    */
  def catsForwards(owner: String, operations: Vector[Operation]): String = {
    val simple = requireWrappable(owner)
    // self.execution on Server[F] itself; self.server.execution on every other handle, which
    // carries a server: Server[F] but no execution of its own.
    val executionReceiver =
      if (owner == "io.github.libtmux.Server") "self" else "self.server"
    // Session.server()/Window.server()/Pane.server()/Client.server() answer exactly the Server[F]
    // scope those classes already carry as a handwritten `server` field; generating a same-named
    // forward would be a real member collision (-Werror already catches it: "extension will never
    // be selected"), not a drift risk to guard against, so this skips it instead of generating dead
    // code the compiler would refuse anyway.
    val skippingOwnServerField =
      if (owner == "io.github.libtmux.Server") operations
      else
        operations.filterNot(op => op.name == "server" && op.parameters.isEmpty)
    val body = skippingOwnServerField
      .map { op =>
        val (params, javaArgs, returnType) = signature(op, CatsStyle)
        val returnMapped = mapType(op.returns.tpe, CatsStyle, owner)
        // self.underlying.asJava.op(...), never self.underlying.op(...): the latter would resolve
        // through the direct-style module's own generated extension, a top-level def with the same
        // name this file's own extension clauses also declare (for other owners) — outer-package
        // top-level definitions are visible, unqualified, to a nested package, so Scala would see
        // both as candidates from different "toplevel definition groups" and refuse to pick one.
        // Going straight to the real Java member sidesteps extension resolution entirely.
        val javaCall = s"self.underlying.asJava.${op.name}($javaArgs)"
        op.kind match {
          case "CAPTURED" =>
            val converted =
              if (returnType == "Unit") javaCall
              else returnMapped.toScala(javaCall)
            s"${scaladoc("    ")(op)}    def ${op.name}${parenthesized(op, params)}: $returnType =\n      $converted"
          case _ =>
            val inner =
              if (returnType == "Unit") javaCall
              else returnMapped.toScala(javaCall)
            s"${scaladoc("    ")(op)}    def ${op.name}($params): F[$returnType] =\n      $executionReceiver.execution($inner)"
        }
      }
      .mkString("\n\n")
    s"""  extension [F[_]](self: $simple[F])(using F: _root_.cats.effect.Async[F]) {
       |
       |$body
       |  }""".stripMargin
  }

  /** One combined file for every owner the fixture (or real) catalog names,
    * direct style. Scala 3 requires same-named top-level definitions (`kill`,
    * `info`, ... repeat across owners) to share one compilation unit — "the
    * same group of toplevel definitions" — so this emits one file for the whole
    * project rather than one per owner.
    */
  def combinedDirectStyle(catalog: Catalog): String = {
    val blocks = byOwner(catalog).map { case (owner, operations) =>
      directStyle(owner, operations)
    }
    s"""package io.github.libtmux.scaladsl
       |
       |// Generated by ScalaCodegen from an operation catalog. Not checked in; see project/ScalaCodegen.scala.
       |
       |${blocks.mkString("\n\n")}
       |""".stripMargin
  }

  /** As [[combinedDirectStyle]], for the Cats forwards. */
  def combinedCatsForwards(catalog: Catalog): String = {
    val blocks = byOwner(catalog).map { case (owner, operations) =>
      catsForwards(owner, operations)
    }
    s"""package io.github.libtmux.scaladsl.cats
       |
       |// Generated by ScalaCodegen from an operation catalog. Not checked in; see project/ScalaCodegen.scala.
       |
       |${blocks.mkString("\n\n")}
       |""".stripMargin
  }

  /** Every wrappable owner (`Server`, `Session`, `Window`, `Pane`, `Client`)
    * the catalog names, grouped in catalog order, restricted to the kinds this
    * generator emits. The real catalog also names owners this facade has no
    * opaque or Cats type for yet (`Options`, `Environment`, `Hooks`, `Buffers`,
    * `Channel`, `CommandChain`, `Batch`, `ControlClient`, `EventSubscription`,
    * `ServerMirror`) — their operations are skipped here, not generated, until
    * those wrapper types exist; a caller reaches them today through the
    * passthrough Java value a wrapped owner's own operation returns
    * (`server.asJava.options()`, ...).
    *
    * @throws RuntimeException
    *   if an operation carries a kind this generator does not know — the gate
    *   the codegen self-test in `build.sbt`'s `core / codegenSelfTest` breaks
    *   on purpose.
    */
  def byOwner(catalog: Catalog): Vector[(String, Vector[Operation])] = {
    val known =
      Set("CAPTURED", "READ", "MUTATION", "WAIT", "STREAM", "LIFECYCLE")
    catalog.operations.foreach(op =>
      require(
        known.contains(op.kind),
        s"unknown operation kind '${op.kind}' on ${op.owner}#${op.name}"
      )
    )
    val generatable = catalog.operations.filter(op =>
      generatableKinds.contains(op.kind) && wrappedHandles.contains(op.owner)
    )
    val owners = generatable.map(_.owner).distinct
    owners.map(owner => owner -> generatable.filter(_.owner == owner))
  }
}
