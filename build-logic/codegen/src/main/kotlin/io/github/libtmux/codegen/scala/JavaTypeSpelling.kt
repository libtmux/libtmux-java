package io.github.libtmux.codegen.scala

/**
 * A Java type as the operation catalog spells it: a canonical source name with type arguments, a
 * primitive, or an array. Parsed fully, because the Scala mapping recurses into type arguments.
 */
sealed interface JavaTypeSpelling {
    data class Primitive(val name: String) : JavaTypeSpelling

    data class Named(val fqn: String, val arguments: List<JavaTypeSpelling> = emptyList()) : JavaTypeSpelling

    data class ArrayOf(val element: JavaTypeSpelling) : JavaTypeSpelling

    companion object {
        private val PRIMITIVES = setOf("int", "long", "short", "byte", "char", "boolean", "float", "double", "void")

        /** Parses one spelling, e.g. `java.util.Optional<io.github.libtmux.Session>` or `java.lang.String[]`. */
        fun parse(spelling: String): JavaTypeSpelling {
            val trimmed = spelling.trim()
            if (trimmed.endsWith("[]")) {
                return ArrayOf(parse(trimmed.dropLast(2)))
            }
            if (trimmed in PRIMITIVES) {
                return Primitive(trimmed)
            }
            val angle = trimmed.indexOf('<')
            if (angle < 0) {
                return Named(trimmed)
            }
            require(trimmed.endsWith(">")) { "unbalanced type arguments in '$spelling'" }
            return Named(trimmed.substring(0, angle), splitTopLevel(trimmed.substring(angle + 1, trimmed.length - 1)).map(::parse))
        }

        /** Splits `A, B<C, D>, E` on its top-level commas only. */
        private fun splitTopLevel(text: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            text.forEachIndexed { index, c ->
                when {
                    c == '<' -> depth++
                    c == '>' -> depth--
                    c == ',' && depth == 0 -> {
                        parts += text.substring(start, index)
                        start = index + 1
                    }
                }
            }
            parts += text.substring(start)
            return parts.map(String::trim)
        }
    }
}
