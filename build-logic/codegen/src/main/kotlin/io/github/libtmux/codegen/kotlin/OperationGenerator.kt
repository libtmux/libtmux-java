package io.github.libtmux.codegen.kotlin

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.UNIT
import io.github.libtmux.codegen.catalog.Catalog
import io.github.libtmux.codegen.catalog.CatalogOperation

/** The package every hand-written wrapper class, and everything this tool generates, lives in. */
public const val FACADE_PACKAGE: String = "io.github.libtmux.kotlin"

/** Every Java handle type this generator knows how to wrap and unwrap. */
internal val WRAPPED_HANDLES: Map<String, ClassName> = mapOf(
    "io.github.libtmux.Server" to ClassName(FACADE_PACKAGE, "Server"),
    "io.github.libtmux.Session" to ClassName(FACADE_PACKAGE, "Session"),
    "io.github.libtmux.Window" to ClassName(FACADE_PACKAGE, "Window"),
    "io.github.libtmux.Pane" to ClassName(FACADE_PACKAGE, "Pane"),
    "io.github.libtmux.Client" to ClassName(FACADE_PACKAGE, "Client"),
    "io.github.libtmux.control.ControlClient" to ClassName(FACADE_PACKAGE, "ControlClient"),
    "io.github.libtmux.Hooks" to ClassName(FACADE_PACKAGE, "Hooks"),
    "io.github.libtmux.Options" to ClassName(FACADE_PACKAGE, "Options"),
    "io.github.libtmux.Shell" to ClassName(FACADE_PACKAGE, "Shell"),
    "io.github.libtmux.Commands" to ClassName(FACADE_PACKAGE, "Commands"),
    "io.github.libtmux.Buffers" to ClassName(FACADE_PACKAGE, "Buffers"),
    "io.github.libtmux.Environment" to ClassName(FACADE_PACKAGE, "Environment"),
    "io.github.libtmux.MessageLog" to ClassName(FACADE_PACKAGE, "MessageLog"),
    "io.github.libtmux.Prompt" to ClassName(FACADE_PACKAGE, "Prompt"),
    "io.github.libtmux.Keys" to ClassName(FACADE_PACKAGE, "Keys"),
    "io.github.libtmux.batch.Batch" to ClassName(FACADE_PACKAGE, "Batch"),
    "io.github.libtmux.CommandChain" to ClassName(FACADE_PACKAGE, "CommandChain"),
)

/** Kinds the generator mirrors as a `suspend` extension function. Everything else is handwritten. */
private val GENERATED_KINDS = setOf("READ", "MUTATION")

/**
 * Catalogued operations a handwritten member already covers under a different contract, so the
 * generator must never emit them: a generated extension would be shadowed by the member, which the
 * module's `allWarningsAsErrors` turns into a build failure. `owner#name(erasedParamType,...)`, matching the stable id
 * `operation-catalog-schema.md` names.
 *
 * `Server#session(FilterExpr)`/`window(FilterExpr)`/`pane(FilterExpr)` return `Optional` in Java;
 * the handwritten [io.github.libtmux.kotlin.Server.session] throws on no match instead, as Kotlin's
 * `single()` does, and [io.github.libtmux.kotlin.Server.sessionOrNull] is its generator-can-never-emit sibling.
 *
 * `Options#get(String)`/`set(String, String)` sit beside a generic `OptionKey<T>` overload of the
 * same name the generator cannot emit (no method type parameter support); once that overload is a
 * handwritten member, it shadows any generated extension of the same name outright; both overloads
 * of [io.github.libtmux.kotlin.Options.get]/[io.github.libtmux.kotlin.Options.set] are handwritten
 * together for that reason.
 */
private val HANDWRITTEN_OVERRIDES = setOf(
    "io.github.libtmux.Server#session(io.github.libtmux.query.FilterExpr)",
    "io.github.libtmux.Server#window(io.github.libtmux.query.FilterExpr)",
    "io.github.libtmux.Server#pane(io.github.libtmux.query.FilterExpr)",
    "io.github.libtmux.Options#get(java.lang.String)",
    "io.github.libtmux.Options#set(java.lang.String,java.lang.String)",
)

/** The stable id `operation-catalog-schema.md` describes: erased parameter types, joined. */
private fun stableId(operation: CatalogOperation): String {
    val erasedParams = operation.parameters.joinToString(",") { parseJavaType(it.type).erasedName() }
    return "${operation.owner}#${operation.name}($erasedParams)"
}

private fun JavaType.erasedName(): String = when (this) {
    JavaType.Void -> "void"
    is JavaType.Primitive -> kotlinName.lowercase()
    is JavaType.Opaque -> fqcn
    is JavaType.ListOf -> "java.util.List"
    is JavaType.SetOf -> "java.util.Set"
    is JavaType.MapOf -> "java.util.Map"
    is JavaType.OptionalOf -> "java.util.Optional"
    JavaType.OptionalInt -> "java.util.OptionalInt"
    JavaType.OptionalLong -> "java.util.OptionalLong"
    is JavaType.FilterExprOf -> "io.github.libtmux.query.FilterExpr"
    is JavaType.ArrayOf -> "${element.erasedName()}[]"
    is JavaType.ConsumerOf -> "java.util.function.Consumer"
}

private fun classNameOf(fqcn: String): ClassName {
    val simple = fqcn.substringAfterLast('.')
    val pkg = fqcn.removeSuffix(".$simple")
    return ClassName(pkg, simple)
}

private fun kotlinTypeName(type: JavaType): TypeName = when (type) {
    JavaType.Void -> UNIT
    is JavaType.Primitive -> ClassName("kotlin", type.kotlinName)
    is JavaType.Opaque -> when (type.fqcn) {
        "java.lang.String" -> STRING
        "java.time.Duration" -> ClassName("kotlin.time", "Duration")
        else -> WRAPPED_HANDLES[type.fqcn] ?: classNameOf(type.fqcn)
    }
    is JavaType.ListOf -> LIST.parameterizedBy(kotlinTypeName(type.element))
    is JavaType.SetOf -> SET.parameterizedBy(kotlinTypeName(type.element))
    is JavaType.MapOf -> MAP.parameterizedBy(kotlinTypeName(type.key), kotlinTypeName(type.value))
    is JavaType.OptionalOf -> kotlinTypeName(type.element).copy(nullable = true)
    JavaType.OptionalInt -> INT.copy(nullable = true)
    JavaType.OptionalLong -> LONG.copy(nullable = true)
    is JavaType.FilterExprOf ->
        ClassName("io.github.libtmux.query", "FilterExpr").parameterizedBy(classNameOf(type.elementFqcn))
    is JavaType.ArrayOf -> kotlinTypeName(type.element)
    is JavaType.ConsumerOf ->
        error("a Consumer-typed parameter reached kotlinTypeName; isGeneratable should have filtered it out")
}

/**
 * Whether this generates a `suspend` extension function: the right [operation.kind], a wrapped
 * owner, no method type parameter (a generic signature such as `Options#get(OptionKey<T>)` stays
 * hand-written), no [JavaType.ConsumerOf] parameter (its Spec-typed sibling generates instead —
 * see `design-kotlin.md` §6a), and not one of [HANDWRITTEN_OVERRIDES].
 */
public fun isGeneratable(operation: CatalogOperation): Boolean {
    if (operation.kind !in GENERATED_KINDS) return false
    if (!WRAPPED_HANDLES.containsKey(operation.owner)) return false
    if (operation.typeParameters.isNotEmpty()) return false
    if (operation.parameters.any { parseJavaType(it.type) is JavaType.ConsumerOf }) return false
    if (stableId(operation) in HANDWRITTEN_OVERRIDES) return false
    return true
}

/**
 * Builds one `FileSpec` per owner, each holding every [isGeneratable] operation for that owner as a
 * `suspend` extension function on the matching wrapper class. What makes this safe to regenerate: these are always extensions, never members, so a hand-written member of
 * the same name silently wins were one to collide, and the module's `allWarningsAsErrors` build turns
 * that silence into a build failure instead.
 */
public fun generateFiles(catalog: Catalog): List<FileSpec> {
    val byOwner = catalog.operations.filter(::isGeneratable).groupBy { it.owner }
    return byOwner.entries.sortedBy { it.key }.map { (owner, operations) ->
        val handle = WRAPPED_HANDLES.getValue(owner)
        val file = FileSpec.builder(FACADE_PACKAGE, "${handle.simpleName}Operations")
            .addFileComment(
                "GENERATED by build-logic/codegen from operation-catalog.json. Do not edit;\n" +
                    "regenerate with the generateOperationWrappers Gradle task.",
            )
            .addImport("kotlinx.coroutines", "runInterruptible")
            .addImport("kotlin.time", "toJavaDuration")
        operations.sortedWith(compareBy({ it.name }, { it.parameters.size }))
            .forEach { file.addFunction(generateFunction(it, handle)) }
        file.build()
    }
}

private fun generateFunction(operation: CatalogOperation, owner: ClassName): FunSpec {
    val serverExpr = if (owner.simpleName == "Server") "this" else "this.server"
    val dispatcherExpr = if (owner.simpleName == "Server") "policy.commands" else "server.policy.commands"

    val builder = FunSpec.builder(operation.name)
        .addModifiers(KModifier.PUBLIC, KModifier.SUSPEND)
        .receiver(owner)
    if (operation.summary.isNotBlank()) {
        builder.addKdoc("%L\n", javadocToKdoc(operation.summary))
    }

    val callArguments = mutableListOf<String>()
    for (parameter in operation.parameters) {
        val parsed = parseJavaType(parameter.type)
        val isVararg = operation.varargs && parsed is JavaType.ArrayOf
        val elementType = if (parsed is JavaType.ArrayOf) parsed.element else parsed
        val paramSpec = ParameterSpec.builder(parameter.name, kotlinTypeName(elementType))
        if (isVararg) paramSpec.addModifiers(KModifier.VARARG)
        builder.addParameter(paramSpec.build())
        callArguments += javaCallArgument(parameter.name, elementType, spread = isVararg)
    }

    val returnType = parseJavaType(operation.returns.type)
    if (returnType != JavaType.Void) {
        builder.returns(kotlinTypeName(returnType))
    }
    val javaCall = "java.${operation.name}(${callArguments.joinToString(", ")})"
    val wrapped = wrapReturn(javaCall, returnType, serverExpr)
    builder.addStatement("return runInterruptible(%L) { %L }", dispatcherExpr, wrapped)
    return builder.build()
}

private fun javaCallArgument(name: String, type: JavaType, spread: Boolean): String {
    val prefix = if (spread) "*" else ""
    val expression = when {
        type is JavaType.Opaque && WRAPPED_HANDLES.containsKey(type.fqcn) -> "$name.java"
        type is JavaType.Opaque && type.fqcn == "java.time.Duration" -> "$name.toJavaDuration()"
        else -> name
    }
    return "$prefix$expression"
}

private fun wrapReturn(javaCall: String, type: JavaType, serverExpr: String): String = when (type) {
    JavaType.Void -> javaCall
    is JavaType.Opaque ->
        WRAPPED_HANDLES[type.fqcn]?.let { "${it.simpleName}($javaCall, $serverExpr)" } ?: javaCall
    is JavaType.ListOf -> {
        val element = type.element
        if (element is JavaType.Opaque && WRAPPED_HANDLES.containsKey(element.fqcn)) {
            "$javaCall.map { ${WRAPPED_HANDLES.getValue(element.fqcn).simpleName}(it, $serverExpr) }"
        } else {
            javaCall
        }
    }
    is JavaType.OptionalOf -> {
        val element = type.element
        val mapped = if (element is JavaType.Opaque && WRAPPED_HANDLES.containsKey(element.fqcn)) {
            "$javaCall.map { ${WRAPPED_HANDLES.getValue(element.fqcn).simpleName}(it, $serverExpr) }"
        } else {
            javaCall
        }
        "$mapped.orElse(null)"
    }
    JavaType.OptionalInt -> "$javaCall.let { if (it.isPresent) it.asInt else null }"
    JavaType.OptionalLong -> "$javaCall.let { if (it.isPresent) it.asLong else null }"
    is JavaType.MapOf, is JavaType.Primitive, is JavaType.FilterExprOf,
    is JavaType.ArrayOf, is JavaType.ConsumerOf, is JavaType.SetOf,
    -> javaCall
}
