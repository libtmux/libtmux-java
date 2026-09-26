package io.github.libtmux.codegen.java

import com.palantir.javapoet.ClassName
import com.palantir.javapoet.CodeBlock
import com.palantir.javapoet.FieldSpec
import com.palantir.javapoet.JavaFile
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.ParameterizedTypeName
import com.palantir.javapoet.TypeName
import com.palantir.javapoet.TypeSpec
import io.github.libtmux.codegen.catalog.FieldRow
import javax.lang.model.element.Modifier

private const val PACKAGE = "io.github.libtmux"
private val FIELDS = ClassName.get("$PACKAGE.query", "Fields")

/** The four owners, in the order their generated classes are written. */
val FIELD_CATALOG_OWNERS = listOf("Pane", "Session", "Window", "Client")

private val CLASS_JAVADOC = mapOf(
    "Pane" to "Typed fields of {@link Pane}.",
    "Session" to
        "Typed fields of {@link Session}, for building an expression that can be read as well as run.\n\n" +
            "<p>Each field exposes only the operators its type supports, so asking a flag to start with a\n" +
            "string does not compile. Every field names a tmux format, so a backend model can bind the handle\n" +
            "before translating the expression into tmux's own {@code -f} filter.",
    "Window" to "Typed fields of {@link Window}.",
    "Client" to "Typed fields of {@link Client}.",
)

/** Renders one owner's rows as the `<Owner>_` class JavaPoet already builds `Pane_`/etc. from. */
object FieldMetamodelWriter {

    fun write(owner: String, rows: List<FieldRow>): JavaFile {
        val ownerType = ClassName.get(PACKAGE, owner)
        val type = TypeSpec.classBuilder("${owner}_")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("\$L", requireNotNull(CLASS_JAVADOC[owner]) { "no class Javadoc for owner $owner" })
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())

        for (row in rows) {
            val constantName = toConstantName(row.name)
            val fieldType = fieldTypeOf(ownerType, row)
            val initializer = initializerOf(row)

            type.addField(
                FieldSpec.builder(fieldType, constantName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer(initializer)
                    .build(),
            )
            type.addMethod(
                MethodSpec.methodBuilder(row.name)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(fieldType)
                    .addJavadoc("\$L", row.javadoc)
                    .addStatement("return \$L", constantName)
                    .build(),
            )
        }

        return JavaFile.builder(PACKAGE, type.build()).skipJavaLangImports(true).build()
    }

    private fun fieldTypeOf(ownerType: ClassName, row: FieldRow): TypeName {
        if (row.isRelation) {
            val (relationKind, targetSimpleName) = row.relationTarget()
            val targetType = ClassName.get(PACKAGE, targetSimpleName)
            val nested = if (relationKind == "to-many") "ToManyRef" else "ToOneRef"
            return ParameterizedTypeName.get(FIELDS.nestedClass(nested), ownerType, targetType)
        }
        val nested = when (row.kind) {
            "TEXT" -> "TextField"
            "NUMBER" -> "NumberField"
            "FLAG" -> "FlagField"
            else -> error("unreachable: FieldRow validates kind, found '${row.kind}'")
        }
        return ParameterizedTypeName.get(FIELDS.nestedClass(nested), ownerType)
    }

    private fun initializerOf(row: FieldRow) =
        if (row.isRelation) {
            val (relationKind, _) = row.relationTarget()
            val factory = if (relationKind == "to-many") "toMany" else "toOne"
            CodeBlock.of("\$T.\$L(\$S, \$L)", FIELDS, factory, row.name, row.accessor)
        } else {
            val factory = when (row.kind) {
                "TEXT" -> "text"
                "NUMBER" -> "number"
                "FLAG" -> "flag"
                else -> error("unreachable: FieldRow validates kind, found '${row.kind}'")
            }
            CodeBlock.of("\$T.\$L(\$S, \$L)", FIELDS, factory, row.tmuxFormat, row.accessor)
        }

    /** `atTop` becomes `AT_TOP`; a name with no upper-case letter becomes its own upper case. */
    private fun toConstantName(name: String): String {
        val builder = StringBuilder()
        for (char in name) {
            if (char.isUpperCase() && builder.isNotEmpty()) {
                builder.append('_')
            }
            builder.append(char.uppercaseChar())
        }
        return builder.toString()
    }
}
