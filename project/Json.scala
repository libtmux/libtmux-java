/** A minimal JSON reader for the meta-build.
  *
  * `operation-catalog.json` is a fixed, small schema (see
  * `libtmux-scala/project/OperationCatalog.scala`), so this avoids adding a
  * JSON library dependency to the sbt meta-build for one file. It reads
  * objects, arrays, strings (with the escapes JSON defines), numbers, booleans
  * and `null`.
  */
object Json {
  sealed trait Value
  final case class JObject(fields: Map[String, Value]) extends Value
  final case class JArray(elements: Vector[Value]) extends Value
  final case class JString(value: String) extends Value
  final case class JNumber(value: Double) extends Value
  final case class JBoolean(value: Boolean) extends Value
  case object JNull extends Value

  def parse(text: String): Value = {
    val parser = new Parser(text)
    val value = parser.parseValue()
    parser.skipWhitespace()
    require(parser.atEnd, "trailing content after the top-level JSON value")
    value
  }

  implicit class ValueOps(private val value: Value) extends AnyVal {
    def field(name: String): Value = value match {
      case JObject(fields) =>
        fields.getOrElse(
          name,
          sys.error(s"missing JSON field '$name' in $value")
        )
      case other =>
        sys.error(s"expected an object to read '$name', found $other")
    }

    def fieldOrElse(name: String, default: => Value): Value = value match {
      case JObject(fields) => fields.getOrElse(name, default)
      case other           =>
        sys.error(s"expected an object to read '$name', found $other")
    }

    def asString: String = value match {
      case JString(text) => text
      case other         => sys.error(s"expected a JSON string, found $other")
    }

    def asBoolean: Boolean = value match {
      case JBoolean(flag) => flag
      case other          => sys.error(s"expected a JSON boolean, found $other")
    }

    def asArray: Vector[Value] = value match {
      case JArray(elements) => elements
      case other            => sys.error(s"expected a JSON array, found $other")
    }

    def asObject: Map[String, Value] = value match {
      case JObject(fields) => fields
      case other           => sys.error(s"expected a JSON object, found $other")
    }
  }

  private final class Parser(text: String) {
    private var pos = 0

    def atEnd: Boolean = pos >= text.length

    def skipWhitespace(): Unit =
      while (pos < text.length && text.charAt(pos).isWhitespace) pos += 1

    def parseValue(): Value = {
      skipWhitespace()
      require(pos < text.length, "unexpected end of JSON input")
      text.charAt(pos) match {
        case '{' => parseObject()
        case '[' => parseArray()
        case '"' => JString(parseString())
        case 't' => literal("true", JBoolean(true))
        case 'f' => literal("false", JBoolean(false))
        case 'n' => literal("null", JNull)
        case _   => parseNumber()
      }
    }

    private def literal(word: String, value: Value): Value = {
      require(text.startsWith(word, pos), s"expected '$word' at position $pos")
      pos += word.length
      value
    }

    private def expect(c: Char): Unit = {
      require(
        pos < text.length && text.charAt(pos) == c,
        s"expected '$c' at position $pos"
      )
      pos += 1
    }

    private def parseObject(): JObject = {
      expect('{')
      skipWhitespace()
      var fields = Map.empty[String, Value]
      if (pos < text.length && text.charAt(pos) == '}') {
        pos += 1; return JObject(fields)
      }
      var continue = true
      while (continue) {
        skipWhitespace()
        val key = parseString()
        skipWhitespace()
        expect(':')
        val value = parseValue()
        fields += key -> value
        skipWhitespace()
        text.charAt(pos) match {
          case ','   => pos += 1
          case '}'   => pos += 1; continue = false
          case other =>
            sys.error(s"expected ',' or '}' at position $pos, found '$other'")
        }
      }
      JObject(fields)
    }

    private def parseArray(): JArray = {
      expect('[')
      skipWhitespace()
      var elements = Vector.empty[Value]
      if (pos < text.length && text.charAt(pos) == ']') {
        pos += 1; return JArray(elements)
      }
      var continue = true
      while (continue) {
        elements :+= parseValue()
        skipWhitespace()
        text.charAt(pos) match {
          case ','   => pos += 1
          case ']'   => pos += 1; continue = false
          case other =>
            sys.error(s"expected ',' or ']' at position $pos, found '$other'")
        }
      }
      JArray(elements)
    }

    private def parseString(): String = {
      expect('"')
      val builder = new StringBuilder
      var continue = true
      while (continue) {
        require(pos < text.length, "unterminated JSON string")
        val c = text.charAt(pos)
        if (c == '"') { pos += 1; continue = false }
        else if (c == '\\') {
          pos += 1
          text.charAt(pos) match {
            case '"'  => builder.append('"'); pos += 1
            case '\\' => builder.append('\\'); pos += 1
            case '/'  => builder.append('/'); pos += 1
            case 'b'  => builder.append('\b'); pos += 1
            case 'f'  => builder.append('\f'); pos += 1
            case 'n'  => builder.append('\n'); pos += 1
            case 'r'  => builder.append('\r'); pos += 1
            case 't'  => builder.append('\t'); pos += 1
            case 'u'  =>
              val hex = text.substring(pos + 1, pos + 5)
              builder.append(Integer.parseInt(hex, 16).toChar)
              pos += 5
            case other =>
              sys.error(s"invalid escape '\\$other' at position $pos")
          }
        } else {
          builder.append(c)
          pos += 1
        }
      }
      builder.toString
    }

    private def parseNumber(): JNumber = {
      val start = pos
      if (
        pos < text.length && (text
          .charAt(pos) == '-' || text.charAt(pos) == '+')
      ) pos += 1
      while (
        pos < text.length && (text.charAt(pos).isDigit || "eE.+-".contains(
          text.charAt(pos)
        ))
      ) pos += 1
      require(pos > start, s"expected a number at position $start")
      JNumber(text.substring(start, pos).toDouble)
    }
  }
}
