package io.github.libtmux.codegen.kotlin

/**
 * Converts a Javadoc summary to KDoc, per `operation-catalog-schema.md`: `{@code x}` becomes `` `x` ``
 * and `{@link A#b}` becomes `[A.b]`. Anything else passes through unchanged — the summary is already
 * plain prose ending at the first period, so no other inline tag is expected here.
 */
public fun javadocToKdoc(summary: String): String {
    val code = Regex("\\{@code ([^}]*)}").replace(summary) { "`${it.groupValues[1]}`" }
    return Regex("\\{@link ([^}]*)}").replace(code) { match ->
        "[${match.groupValues[1].trim().replace('#', '.')}]"
    }
}
