/** The meta-build's reader for `operation-catalog.json`.
  *
  * The schema is fixed by
  * [[https://github.com/libtmux/libtmux-java `operation-catalog-schema.md`]]:
  * one record per `@Operation` method, owners sorted by name, methods in source
  * order. `ScalaCodegen` turns parsed operations into direct-style extensions
  * and Cats forwards; this file only parses and models them.
  */
object OperationCatalog {

  /** A Java type as the catalog spells it: a canonical source name plus type
    * arguments, or an array.
    */
  sealed trait JType
  final case class Primitive(name: String) extends JType
  final case class Named(fqn: String, args: Vector[JType] = Vector.empty)
      extends JType
  final case class ArrayOf(element: JType) extends JType

  final case class TypeUse(tpe: JType, nullable: Boolean)
  final case class Parameter(name: String, tpe: TypeUse)
  final case class Javadoc(summary: String)
  final case class Operation(
      owner: String,
      name: String,
      kind: String,
      static: Boolean,
      varargs: Boolean,
      parameters: Vector[Parameter],
      returns: TypeUse,
      javadoc: Javadoc
  )
  final case class Catalog(schema: Int, operations: Vector[Operation])

  private val primitiveNames =
    Set(
      "int",
      "long",
      "short",
      "byte",
      "char",
      "boolean",
      "float",
      "double",
      "void"
    )

  /** Parses one canonical Java type spelling, e.g.
    * `java.util.Optional<io.github.libtmux.Session>` or `java.lang.String[]`.
    */
  def parseType(spelling: String): JType = {
    val trimmed = spelling.trim
    if (trimmed.endsWith("[]")) ArrayOf(parseType(trimmed.dropRight(2)))
    else if (primitiveNames.contains(trimmed)) Primitive(trimmed)
    else {
      val angle = trimmed.indexOf('<')
      if (angle < 0) Named(trimmed)
      else {
        require(
          trimmed.endsWith(">"),
          s"unbalanced type arguments in '$spelling'"
        )
        val fqn = trimmed.substring(0, angle)
        val argsText = trimmed.substring(angle + 1, trimmed.length - 1)
        Named(fqn, splitTypeArguments(argsText).map(parseType))
      }
    }
  }

  /** Splits `A, B<C, D>, E` on the top-level commas only. */
  private def splitTypeArguments(text: String): Vector[String] = {
    var depth = 0
    var start = 0
    var parts = Vector.empty[String]
    var index = 0
    while (index < text.length) {
      text.charAt(index) match {
        case '<'               => depth += 1
        case '>'               => depth -= 1
        case ',' if depth == 0 =>
          parts :+= text.substring(start, index)
          start = index + 1
        case _ =>
      }
      index += 1
    }
    parts :+= text.substring(start)
    parts.map(_.trim)
  }

  def parse(json: String): Catalog = {
    import Json._
    val root = Json.parse(json)
    val schema = root.field("schema") match {
      case JNumber(value) => value.toInt
      case other          => sys.error(s"schema must be a number, found $other")
    }
    val operations = root.field("operations").asArray.map(parseOperation)
    Catalog(schema, operations)
  }

  private def parseOperation(value: Json.Value): Operation = {
    import Json._
    val parameters = value
      .fieldOrElse("parameters", JArray(Vector.empty))
      .asArray
      .map(parseParameter)
    Operation(
      owner = value.field("owner").asString,
      name = value.field("name").asString,
      kind = value.field("kind").asString,
      static = value.fieldOrElse("static", JBoolean(false)).asBoolean,
      varargs = value.fieldOrElse("varargs", JBoolean(false)).asBoolean,
      parameters = parameters,
      returns = parseTypeUse(value.field("returns")),
      javadoc = parseJavadoc(value.fieldOrElse("javadoc", JObject(Map.empty)))
    )
  }

  private def parseParameter(value: Json.Value): Parameter =
    Parameter(value.field("name").asString, parseTypeUse(value))

  private def parseTypeUse(value: Json.Value): TypeUse = {
    import Json._
    TypeUse(
      tpe = parseType(value.field("type").asString),
      nullable = value.fieldOrElse("nullable", JBoolean(false)).asBoolean
    )
  }

  private def parseJavadoc(value: Json.Value): Javadoc = {
    import Json._
    Javadoc(summary = value.fieldOrElse("summary", JString("")).asString)
  }
}
