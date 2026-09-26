package io.github.libtmux.codegen.scala

import io.github.libtmux.codegen.catalog.Catalog
import io.github.libtmux.codegen.catalog.CatalogOperation
import io.github.libtmux.codegen.catalog.CatalogParameter

/**
 * Turns catalogued operations into Scala 3 source, by string template: the direct-style extension
 * methods of `libtmux-scala` and the per-operation forwards of `libtmux-scala-cats`.
 *
 * Only `CAPTURED`, `READ` and `MUTATION` operations are generated. `WAIT`, `STREAM` and `LIFECYCLE`
 * need bespoke cancellation or resource scoping, so they are written by hand; so are `CommandChain`
 * and `Batch`, whose builder replay needs judgment a template cannot supply.
 *
 * The type mapping covers primitives, `String`, `Duration`, `Optional`/`OptionalInt`/`OptionalLong`,
 * `List`, `Map`, the five wrapped handles, builder `Consumer`s, varargs `String...` and `FilterExpr`.
 * Any other type passes through by its canonical name, unconverted: a plain Java value type with no
 * member worth hiding (`WindowId`, `Dimensions`, `ServerSnapshot`, the `*State` records, ...).
 */
object ScalaOperationGenerator {

    /** Which facade a signature is generated for. */
    enum class Target { DIRECT, CATS }

    /** The catalog kinds this generator emits; the rest are handwritten. */
    val GENERATABLE_KINDS: Set<String> = setOf("CAPTURED", "READ", "MUTATION")

    private val KNOWN_KINDS = setOf("CAPTURED", "READ", "MUTATION", "WAIT", "STREAM", "LIFECYCLE")

    private const val SERVER = "io.github.libtmux.Server"

    private val WRAPPED_HANDLES = setOf(
        SERVER,
        "io.github.libtmux.Session",
        "io.github.libtmux.Window",
        "io.github.libtmux.Pane",
        "io.github.libtmux.Client",
    )

    private const val HEADER =
        "// Generated from the operation catalog by build-logic/codegen (ScalaOperationGenerator). Not checked in."

    /**
     * How one Java type crosses the boundary: the Scala type a signature spells, and how to convert a
     * value each way given the expression that produces it.
     */
    class Mapped(val scalaType: String, val toScala: (String) -> String, val toJava: (String) -> String)

    /**
     * Maps [type] for [target]. [owner] is the enclosing operation's owner, needed because a Cats
     * wrap goes through `self` when `self` is the `Server[F]`, and `self.server` otherwise.
     */
    fun mapType(type: JavaTypeSpelling, target: Target, owner: String): Mapped = when {
        type is JavaTypeSpelling.Primitive -> Mapped(primitiveScala(type.name), { it }, { it })
        type is JavaTypeSpelling.Named -> mapNamed(type, target, owner)
        else -> error("ScalaOperationGenerator has no mapping for '$type'")
    }

    private fun mapNamed(type: JavaTypeSpelling.Named, target: Target, owner: String): Mapped {
        val args = type.arguments
        return when (type.fqn) {
            "java.lang.String" -> Mapped("String", { it }, { it })
            "java.time.Duration" -> Mapped(
                "_root_.scala.concurrent.duration.FiniteDuration",
                { j ->
                    "_root_.scala.concurrent.duration.Duration.fromNanos($j.toNanos)" +
                        ".asInstanceOf[_root_.scala.concurrent.duration.FiniteDuration]"
                },
                { s -> "_root_.scala.jdk.DurationConverters.ScalaDurationOps($s).toJava" },
            )
            "java.util.OptionalInt" -> Mapped(
                "Option[Int]",
                { j -> "_root_.scala.jdk.OptionConverters.RichOptionalInt($j).toScala" },
                { s -> "_root_.scala.jdk.OptionConverters.RichOption($s).toJavaPrimitive" },
            )
            "java.util.OptionalLong" -> Mapped(
                "Option[Long]",
                { j -> "_root_.scala.jdk.OptionConverters.RichOptionalLong($j).toScala" },
                { s -> "_root_.scala.jdk.OptionConverters.RichOption($s).toJavaPrimitive" },
            )
            // java.lang.Boolean, not scala.Boolean: the wire type is a Java reference type, but every
            // Scala-facing signature spells it Boolean, matching the primitive; booleanValue()/valueOf
            // convert explicitly rather than leaning on Scala's boxing inference to find it.
            "java.lang.Boolean" -> Mapped(
                "Boolean",
                { j -> "$j.booleanValue()" },
                { s -> "_root_.java.lang.Boolean.valueOf($s)" },
            )
            // A fresh lambda literal, not the bare value: Scala's SAM conversion to Runnable applies to
            // a literal at the argument position, not to an already-built () => Unit value.
            "java.lang.Runnable" -> Mapped("() => Unit", { j -> "() => $j.run()" }, { s -> "(() => $s())" })
            "java.util.Optional" -> container(
                args.single(), target, owner,
                scalaType = { "Option[$it]" },
                toScalaPassthrough = { j -> "_root_.scala.jdk.OptionConverters.RichOptional($j).toScala" },
                toScalaMapped = { j, v -> "_root_.scala.jdk.OptionConverters.RichOptional($j).toScala.map(v => $v)" },
                toJavaPassthrough = { s -> "_root_.scala.jdk.OptionConverters.RichOption($s).toJava" },
                toJavaMapped = { s, v -> "_root_.scala.jdk.OptionConverters.RichOption($s.map(v => $v)).toJava" },
            )
            "java.util.List" -> container(
                args.single(), target, owner,
                scalaType = { "Vector[$it]" },
                toScalaPassthrough = { j -> "_root_.scala.jdk.CollectionConverters.ListHasAsScala($j).asScala.toVector" },
                toScalaMapped = { j, v ->
                    "_root_.scala.jdk.CollectionConverters.ListHasAsScala($j).asScala.iterator.map(v => $v).toVector"
                },
                toJavaPassthrough = { s -> "_root_.scala.jdk.CollectionConverters.SeqHasAsJava($s).asJava" },
                toJavaMapped = { s, v -> "_root_.scala.jdk.CollectionConverters.SeqHasAsJava($s.map(v => $v)).asJava" },
            )
            "java.util.Map" -> mapOf(args, target, owner)
            // A builder-consumer parameter, e.g. Consumer<SplitSpec.Builder>, for the fluent overload
            // (`pane.split(s => s.toRight().percent(30))`). The mutable Java builder is reused as is; a
            // Scala T => Unit converts to Consumer[T] via SAM conversion at the call site, which needs
            // a fresh lambda literal for the same reason Runnable does.
            "java.util.function.Consumer" -> {
                val builder = fqnOf(args.single())
                Mapped("$builder => Unit", { it }, { s -> "((it: $builder) => $s(it))" })
            }
            "io.github.libtmux.query.FilterExpr" -> {
                val rawJava = fqnOf(args.single())
                Mapped(
                    "io.github.libtmux.scaladsl.query.Expr[$rawJava]",
                    { j -> "io.github.libtmux.scaladsl.query.Expr($j)" },
                    { s -> "$s.asJava" },
                )
            }
            in WRAPPED_HANDLES -> handle(type.fqn, target, owner)
            else -> {
                require(args.isEmpty()) { "ScalaOperationGenerator has no mapping for '$type'" }
                Mapped(type.fqn, { it }, { it })
            }
        }
    }

    private fun container(
        inner: JavaTypeSpelling,
        target: Target,
        owner: String,
        scalaType: (String) -> String,
        toScalaPassthrough: (String) -> String,
        toScalaMapped: (String, String) -> String,
        toJavaPassthrough: (String) -> String,
        toJavaMapped: (String, String) -> String,
    ): Mapped {
        val element = mapType(inner, target, owner)
        val passthrough = isPassthrough(inner)
        return Mapped(
            scalaType(element.scalaType),
            { j -> if (passthrough) toScalaPassthrough(j) else toScalaMapped(j, element.toScala("v")) },
            { s -> if (passthrough) toJavaPassthrough(s) else toJavaMapped(s, element.toJava("v")) },
        )
    }

    // Every map key in the catalog (String, PaneId) is passthrough; only the value can need a
    // per-entry conversion (Server.paneFields nests a second Map as its value).
    private fun mapOf(args: List<JavaTypeSpelling>, target: Target, owner: String): Mapped {
        require(args.size == 2) { "java.util.Map needs two type arguments, found $args" }
        val (key, value) = args
        val keyMapped = mapType(key, target, owner)
        val valueMapped = mapType(value, target, owner)
        val passthrough = isPassthrough(value)
        return Mapped(
            "_root_.scala.collection.immutable.VectorMap[${keyMapped.scalaType}, ${valueMapped.scalaType}]",
            { j ->
                if (passthrough) {
                    "_root_.scala.collection.immutable.VectorMap.from(_root_.scala.jdk.CollectionConverters.MapHasAsScala($j).asScala)"
                } else {
                    "_root_.scala.collection.immutable.VectorMap.from(_root_.scala.jdk.CollectionConverters.MapHasAsScala($j)" +
                        ".asScala.map { case (k, v) => k -> ${valueMapped.toScala("v")} })"
                }
            },
            { s ->
                if (passthrough) {
                    "_root_.scala.jdk.CollectionConverters.MapHasAsJava($s).asJava"
                } else {
                    "_root_.scala.jdk.CollectionConverters.MapHasAsJava($s.map { case (k, v) => k -> ${valueMapped.toJava("v")} }).asJava"
                }
            },
        )
    }

    private fun handle(fqn: String, target: Target, owner: String): Mapped {
        val simple = simpleNameOf(fqn)
        return when {
            target == Target.DIRECT -> Mapped(simple, { j -> "$simple.wrap($j)" }, { s -> "$s.asJava" })
            // Every non-Server handle already carries the Server[F] it was captured through (its own
            // `server` field), and Java's Session.server() and friends answer exactly that object, so
            // this reuses the held reference and never makes the Java call.
            simple == "Server" && owner != SERVER ->
                Mapped("Server[F]", { "self.server" }, { s -> "$s.underlying.asJava" })
            else -> {
                val receiver = if (owner == SERVER) "self" else "self.server"
                // Qualified: an unqualified name in the Cats package would resolve to that module's own
                // same-named class, which has no wrap.
                Mapped(
                    "$simple[F]",
                    { j -> "$receiver.${decapitalize(simple)}(io.github.libtmux.scaladsl.$simple.wrap($j))" },
                    { s -> "$s.underlying.asJava" },
                )
            }
        }
    }

    /** Whether [type] needs no per-element conversion inside a container. */
    private fun isPassthrough(type: JavaTypeSpelling): Boolean = when (type) {
        is JavaTypeSpelling.Primitive -> true
        is JavaTypeSpelling.ArrayOf -> false
        is JavaTypeSpelling.Named ->
            type.fqn != "java.lang.Boolean" &&
                type.arguments.isEmpty() &&
                type.fqn !in WRAPPED_HANDLES &&
                type.fqn != "io.github.libtmux.query.FilterExpr"
    }

    private fun primitiveScala(name: String): String = when (name) {
        "int" -> "Int"
        "long" -> "Long"
        "short" -> "Short"
        "byte" -> "Byte"
        "char" -> "Char"
        "boolean" -> "Boolean"
        "float" -> "Float"
        "double" -> "Double"
        "void" -> "Unit"
        else -> error("unknown primitive '$name'")
    }

    private fun fqnOf(type: JavaTypeSpelling): String =
        (type as? JavaTypeSpelling.Named)?.fqn ?: error("expected a named type, found $type")

    private fun simpleNameOf(fqn: String): String = fqn.substring(fqn.lastIndexOf('.') + 1)

    private fun decapitalize(name: String): String = name.replaceFirstChar { it.lowercaseChar() }

    private fun requireWrappable(owner: String): String {
        require(owner in WRAPPED_HANDLES) { "'$owner' is not a wrappable handle" }
        return simpleNameOf(owner)
    }

    /** One parameter's signature fragment, and its Java argument expression at the call site. */
    private fun parameter(p: CatalogParameter, isLast: Boolean, varargs: Boolean, target: Target, owner: String): Pair<String, String> {
        if (isLast && varargs) {
            return "${p.name}: String*" to "${p.name}*"
        }
        val mapped = mapType(JavaTypeSpelling.parse(p.type), target, owner)
        return "${p.name}: ${mapped.scalaType}" to mapped.toJava(p.name)
    }

    private data class Signature(val params: String, val javaArgs: String, val returnType: String)

    private fun signature(op: CatalogOperation, target: Target): Signature {
        val params = op.parameters.mapIndexed { index, p ->
            parameter(p, index == op.parameters.lastIndex, op.varargs, target, op.owner)
        }
        return Signature(
            params.joinToString(", ") { it.first },
            params.joinToString(", ") { it.second },
            mapType(JavaTypeSpelling.parse(op.returns.type), target, op.owner).scalaType,
        )
    }

    private fun scaladoc(op: CatalogOperation): String = if (op.summary.isEmpty()) "" else "    /** ${op.summary} */\n"

    /**
     * A pure, zero-argument operation reads as a property (`def windows: Vector[Window]`); one that
     * reaches tmux keeps its parens even with no arguments, by Scala's convention for a
     * side-effecting nullary call.
     */
    private fun parenthesized(op: CatalogOperation, params: String): String =
        if (params.isEmpty() && op.kind == "CAPTURED") "" else "($params)"

    /** Direct-style extensions for one owner: `extension (self: Pane) def ... = self.asJava.op(...)`. */
    fun directStyle(owner: String, operations: List<CatalogOperation>): String {
        val simple = requireWrappable(owner)
        val body = operations.joinToString("\n\n") { op ->
            val sig = signature(op, Target.DIRECT)
            val call = "self.asJava.${op.name}(${sig.javaArgs})"
            val converted = if (sig.returnType == "Unit") call else mapType(JavaTypeSpelling.parse(op.returns.type), Target.DIRECT, owner).toScala(call)
            "${scaladoc(op)}    def ${op.name}${parenthesized(op, sig.params)}: ${sig.returnType} =\n      $converted"
        }
        return "  extension (self: $simple) {\n\n$body\n  }"
    }

    /**
     * Cats forwards for one owner: `extension [F[_]](self: Pane[F]) def ... = self.server.execution(...)`.
     * `CAPTURED` operations forward purely, with no `F[_]`; `READ` and `MUTATION` run through
     * `Execution[F]`.
     */
    fun catsForwards(owner: String, operations: List<CatalogOperation>): String {
        val simple = requireWrappable(owner)
        // Server[F] owns the execution; every other handle reaches it through its server.
        val executionReceiver = if (owner == SERVER) "self" else "self.server"
        val generated = operations
            // Session.server() and the like would collide with the handwritten `server` field every
            // non-Server Cats handle carries (-Werror: "extension will never be selected").
            .filterNot { owner != SERVER && it.name == "server" && it.parameters.isEmpty() }
            // batch(), chain() and channel() are handwritten on the Cats side: a raw Java Batch or
            // CommandChain runs eagerly and uninterruptibly, and Channel.await blocks the caller, so a
            // pure forward would skip Execution's admission bound and cancellation safety.
            .filterNot { it.name == "batch" || it.name == "chain" || it.name == "channel" }
        val body = generated.joinToString("\n\n") { op ->
            val sig = signature(op, Target.CATS)
            // self.underlying.asJava.op(...), not self.underlying.op(...): the latter would resolve
            // through the direct-style facade's same-named top-level extension, which Scala refuses to
            // choose between; the Java member sidesteps extension resolution entirely.
            val javaCall = "self.underlying.asJava.${op.name}(${sig.javaArgs})"
            val converted = if (sig.returnType == "Unit") javaCall else mapType(JavaTypeSpelling.parse(op.returns.type), Target.CATS, owner).toScala(javaCall)
            if (op.kind == "CAPTURED") {
                "${scaladoc(op)}    def ${op.name}${parenthesized(op, sig.params)}: ${sig.returnType} =\n      $converted"
            } else {
                "${scaladoc(op)}    def ${op.name}(${sig.params}): F[${sig.returnType}] =\n      $executionReceiver.execution($converted)"
            }
        }
        return "  extension [F[_]](self: $simple[F])(using F: _root_.cats.effect.Async[F]) {\n\n$body\n  }"
    }

    /**
     * One file for every owner, direct style. Scala 3 requires same-named top-level definitions
     * (`kill`, `info`, ... repeat across owners) to share one compilation unit, so the whole facade is
     * one file rather than one per owner.
     */
    fun combinedDirectStyle(catalog: Catalog): String =
        combined("io.github.libtmux.scaladsl", byOwner(catalog).map { (owner, ops) -> directStyle(owner, ops) })

    /** As [combinedDirectStyle], for the Cats forwards. */
    fun combinedCatsForwards(catalog: Catalog): String =
        combined("io.github.libtmux.scaladsl.cats", byOwner(catalog).map { (owner, ops) -> catsForwards(owner, ops) })

    private fun combined(pkg: String, blocks: List<String>): String =
        "package $pkg\n\n$HEADER\n\n${blocks.joinToString("\n\n")}\n"

    /**
     * Every wrappable owner the catalog names, in catalog order, with its generatable operations.
     * Owners the facade has no wrapper type for yet (`Options`, `ControlClient`, ...) are skipped; a
     * caller reaches them through the Java value a wrapped owner's operation returns.
     *
     * @throws IllegalArgumentException if an operation carries a kind this generator does not know
     */
    fun byOwner(catalog: Catalog): List<Pair<String, List<CatalogOperation>>> {
        catalog.operations.forEach {
            require(it.kind in KNOWN_KINDS) { "unknown operation kind '${it.kind}' on ${it.owner}#${it.name}" }
        }
        val generatable = catalog.operations.filter { it.kind in GENERATABLE_KINDS && it.owner in WRAPPED_HANDLES }
        return generatable.map { it.owner }.distinct().map { owner -> owner to generatable.filter { it.owner == owner } }
    }
}
