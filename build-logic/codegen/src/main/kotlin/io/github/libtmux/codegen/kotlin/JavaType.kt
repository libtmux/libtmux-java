package io.github.libtmux.codegen.kotlin

/**
 * A Java type as `operation-catalog.json` spells it, parsed just far enough to decide how the
 * generator wraps and unwraps it. Not a general type-string parser: it covers exactly the shapes the
 * core's public methods use today, and a shape it does not recognize is kept as [JavaType.Opaque]
 * rather than guessed at.
 */
public sealed interface JavaType {
    public data object Void : JavaType
    public data class Primitive(val kotlinName: String) : JavaType
    public data class Opaque(val fqcn: String) : JavaType
    public data class ListOf(val element: JavaType) : JavaType
    public data class MapOf(val key: JavaType, val value: JavaType) : JavaType
    public data class OptionalOf(val element: JavaType) : JavaType
    public data object OptionalInt : JavaType
    public data object OptionalLong : JavaType
    public data class FilterExprOf(val elementFqcn: String) : JavaType
    public data class ArrayOf(val element: JavaType) : JavaType

    /**
     * A `java.util.function.Consumer<X.Builder>` parameter, from a builder-configuring overload
     * such as `Pane.capture(Consumer<CaptureSpec.Builder>)`. Never generated (see [isGeneratable]):
     * its sibling overload taking the built spec directly generates instead, and is what the
     * `@DslMarker` builders and Kotlin's own `SessionSpec.builder().apply { }` already cover.
     */
    public data class ConsumerOf(val elementFqcn: String) : JavaType
}

/** Parses a canonical Java type string, as `operation-catalog-schema.md` requires it spelled. */
public fun parseJavaType(raw: String): JavaType {
    val s = raw.trim()
    return when {
        s == "void" -> JavaType.Void
        s == "boolean" -> JavaType.Primitive("Boolean")
        s == "int" -> JavaType.Primitive("Int")
        s == "long" -> JavaType.Primitive("Long")
        s.endsWith("[]") -> JavaType.ArrayOf(parseJavaType(s.removeSuffix("[]")))
        s == "java.util.OptionalInt" -> JavaType.OptionalInt
        s == "java.util.OptionalLong" -> JavaType.OptionalLong
        s.startsWith("java.util.List<") -> JavaType.ListOf(parseJavaType(genericArgument(s)))
        s.startsWith("java.util.Optional<") -> JavaType.OptionalOf(parseJavaType(genericArgument(s)))
        s.startsWith("java.util.Map<") -> {
            val (key, value) = splitTopLevelArguments(genericArgument(s))
            JavaType.MapOf(parseJavaType(key), parseJavaType(value))
        }
        s.startsWith("io.github.libtmux.query.FilterExpr<") -> JavaType.FilterExprOf(genericArgument(s))
        s.startsWith("java.util.function.Consumer<") -> JavaType.ConsumerOf(genericArgument(s))
        else -> JavaType.Opaque(s)
    }
}

private fun genericArgument(s: String): String = s.substringAfter('<').substringBeforeLast('>')

/** Splits `K,V` at its one top-level comma; a `K` or `V` holding its own `<...>` is not split inside it. */
private fun splitTopLevelArguments(s: String): Pair<String, String> {
    var depth = 0
    for (index in s.indices) {
        when (s[index]) {
            '<' -> depth++
            '>' -> depth--
            ',' -> if (depth == 0) return s.substring(0, index).trim() to s.substring(index + 1).trim()
        }
    }
    throw IllegalArgumentException("not two comma-separated type arguments: $s")
}
