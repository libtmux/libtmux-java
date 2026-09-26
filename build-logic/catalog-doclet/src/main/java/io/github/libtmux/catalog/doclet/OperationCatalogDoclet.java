package io.github.libtmux.catalog.doclet;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.EndElementTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.ReturnTree;
import com.sun.source.doctree.StartElementTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.doctree.ThrowsTree;
import com.sun.source.util.DocTreePath;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;
import org.jspecify.annotations.Nullable;

/**
 * Emits {@code operation-catalog.json}: one record per {@code @Operation}-annotated method, each
 * carrying its owner, signature, annotation values, and Javadoc text.
 *
 * <p>Runs over source, not the compiled jar, so it can read the doc comment beside each
 * annotation. See {@code operation-catalog-schema.md} for the JSON shape this writes.
 */
public final class OperationCatalogDoclet implements Doclet {

    private static final String OPERATION_ANNOTATION = "io.github.libtmux.catalog.Operation";
    private static final String NULLABLE_ANNOTATION = "org.jspecify.annotations.Nullable";

    private @Nullable Path outputFile;
    private @Nullable Path markdownFile;
    private @Nullable Reporter reporter;

    @Override
    public void init(Locale locale, Reporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public String getName() {
        return "OperationCatalog";
    }

    @Override
    public Set<? extends Option> getSupportedOptions() {
        return Set.of(
                pathOption("-out", "path to write operation-catalog.json", path -> outputFile = path),
                pathOption(
                        "-markdown-out",
                        "optional path to write a generated operations.md table",
                        path -> markdownFile = path));
    }

    private static Option pathOption(String name, String description, Consumer<Path> sink) {
        return new Option() {
            @Override
            public int getArgumentCount() {
                return 1;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public Option.Kind getKind() {
                return Option.Kind.STANDARD;
            }

            @Override
            public List<String> getNames() {
                return List.of(name);
            }

            @Override
            public String getParameters() {
                return "<file>";
            }

            @Override
            public boolean process(String option, List<String> arguments) {
                sink.accept(Path.of(arguments.get(0)));
                return true;
            }
        };
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean run(DocletEnvironment environment) {
        Path out = outputFile;
        if (out == null) {
            report("the -out option is required");
            return false;
        }

        Elements elements = environment.getElementUtils();
        Types types = environment.getTypeUtils();
        DocTrees trees = environment.getDocTrees();

        List<TypeElement> owners = new ArrayList<>();
        for (Element element : environment.getIncludedElements()) {
            if (isTypeElement(element) && element.getModifiers().contains(Modifier.PUBLIC)) {
                TypeElement type = (TypeElement) element;
                if (declaresAnyOperation(type)) {
                    owners.add(type);
                }
            }
        }
        owners.sort(Comparator.comparing(type -> type.getQualifiedName().toString()));

        List<Map<String, Object>> operations = new ArrayList<>();
        for (TypeElement owner : owners) {
            for (Element member : owner.getEnclosedElements()) {
                if (member.getKind() != ElementKind.METHOD) {
                    continue;
                }
                ExecutableElement method = (ExecutableElement) member;
                AnnotationMirror operation = annotation(method, OPERATION_ANNOTATION);
                if (operation != null) {
                    operations.add(record(elements, types, trees, owner, method, operation));
                }
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", 1);
        root.put("operations", operations);
        write(root, out);

        Path markdown = markdownFile;
        if (markdown != null) {
            writeMarkdown(operations, markdown);
        }
        return true;
    }

    private void report(String message) {
        Reporter r = reporter;
        if (r != null) {
            r.print(Diagnostic.Kind.ERROR, message);
        }
    }

    private static boolean isTypeElement(Element element) {
        return element.getKind().isClass() || element.getKind().isInterface();
    }

    private static boolean declaresAnyOperation(TypeElement type) {
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() == ElementKind.METHOD && annotation(member, OPERATION_ANNOTATION) != null) {
                return true;
            }
        }
        return false;
    }

    private static void write(Map<String, Object> root, Path out) {
        try {
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                JsonWriter.write(root, writer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + out, e);
        }
    }

    /** Owner, method signature, kind, and Javadoc summary, one row per operation, in JSON's own order. */
    private static void writeMarkdown(List<Map<String, Object>> operations, Path out) {
        StringBuilder text = new StringBuilder();
        text.append("# Operations reference\n\n")
                .append("Generated from `@Operation`-annotated methods by the operation-catalog Doclet; ")
                .append("do not edit by hand.\n\n")
                .append("| Owner | Method | Kind | Summary |\n")
                .append("| --- | --- | --- | --- |\n");
        for (Map<String, Object> operation : operations) {
            List<?> parameters = (List<?>) Objects.requireNonNull(operation.get("parameters"));
            StringBuilder signature = new StringBuilder(
                            Objects.requireNonNull(operation.get("name")).toString())
                    .append('(');
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) {
                    signature.append(", ");
                }
                signature.append(((Map<?, ?>) Objects.requireNonNull(parameters.get(i))).get("type"));
            }
            signature.append(')');
            Map<?, ?> javadoc = (Map<?, ?>) Objects.requireNonNull(operation.get("javadoc"));
            text.append("| `")
                    .append(operation.get("owner"))
                    .append("` | `")
                    .append(signature)
                    .append("` | ")
                    .append(operation.get("kind"))
                    .append(" | ")
                    .append(markdownEscape(
                            Objects.requireNonNull(javadoc.get("summary")).toString()))
                    .append(" |\n");
        }
        try {
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + out, e);
        }
    }

    /** Escapes the two characters that would otherwise break a Markdown table row. */
    private static String markdownEscape(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }

    /** One {@code @Operation} method's record: owner, signature, annotation values, and Javadoc. */
    private static Map<String, Object> record(
            Elements elements,
            Types types,
            DocTrees trees,
            TypeElement owner,
            ExecutableElement method,
            AnnotationMirror operation) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("owner", owner.getQualifiedName().toString());
        json.put("name", method.getSimpleName().toString());
        json.put("kind", kind(elements, operation));
        json.put("tmuxSince", tmuxSince(elements, operation));
        json.put("static", method.getModifiers().contains(Modifier.STATIC));
        json.put("varargs", method.isVarArgs());
        json.put("typeParameters", typeParameters(method));
        json.put("parameters", parameters(method));
        json.put("returns", returns(method));
        json.put("throws", declaredCheckedExceptions(types, elements, method));
        json.put("deprecated", elements.isDeprecated(method));
        json.put("javadoc", javadoc(trees, method));
        return json;
    }

    private static String kind(Elements elements, AnnotationMirror operation) {
        for (var entry : elements.getElementValuesWithDefaults(operation).entrySet()) {
            if ("value".equals(entry.getKey().getSimpleName().toString())
                    && entry.getValue().getValue() instanceof VariableElement enumConstant) {
                return enumConstant.getSimpleName().toString();
            }
        }
        throw new IllegalStateException("@Operation with no value(): " + operation);
    }

    private static String tmuxSince(Elements elements, AnnotationMirror operation) {
        for (var entry : elements.getElementValuesWithDefaults(operation).entrySet()) {
            if ("tmuxSince".equals(entry.getKey().getSimpleName().toString())
                    && entry.getValue().getValue() instanceof String since) {
                return since;
            }
        }
        return "";
    }

    private static List<Object> typeParameters(ExecutableElement method) {
        List<Object> typeParameters = new ArrayList<>();
        for (TypeParameterElement typeParameter : method.getTypeParameters()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", typeParameter.getSimpleName().toString());
            List<Object> bounds = new ArrayList<>();
            for (TypeMirror bound : typeParameter.getBounds()) {
                bounds.add(formatType(bound));
            }
            entry.put("bounds", bounds);
            typeParameters.add(entry);
        }
        return typeParameters;
    }

    private static List<Object> parameters(ExecutableElement method) {
        List<Object> parameters = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", parameter.getSimpleName().toString());
            entry.put("type", formatType(parameter.asType()));
            entry.put("nullable", isNullable(parameter.asType()));
            parameters.add(entry);
        }
        return parameters;
    }

    private static Map<String, Object> returns(ExecutableElement method) {
        Map<String, Object> returns = new LinkedHashMap<>();
        returns.put("type", formatType(method.getReturnType()));
        returns.put("nullable", isNullable(method.getReturnType()));
        return returns;
    }

    /** Declared checked exceptions only: neither an unchecked {@link RuntimeException} nor an {@link Error}. */
    private static List<Object> declaredCheckedExceptions(Types types, Elements elements, ExecutableElement method) {
        TypeMirror runtimeException =
                elements.getTypeElement("java.lang.RuntimeException").asType();
        TypeMirror error = elements.getTypeElement("java.lang.Error").asType();
        List<Object> thrown = new ArrayList<>();
        for (TypeMirror thrownType : method.getThrownTypes()) {
            if (!types.isSubtype(thrownType, runtimeException) && !types.isSubtype(thrownType, error)) {
                thrown.add(formatType(thrownType));
            }
        }
        return thrown;
    }

    private static Map<String, Object> javadoc(DocTrees trees, ExecutableElement method) {
        DocCommentTree tree = trees.getDocCommentTree(method);
        Map<String, Object> doc = new LinkedHashMap<>();
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> throwsMap = new LinkedHashMap<>();
        String returns = null;
        if (tree != null) {
            TreePath methodPath = trees.getPath(method);
            for (DocTree block : tree.getBlockTags()) {
                switch (block) {
                    case ParamTree p -> {
                        if (!p.isTypeParameter()) {
                            params.put(p.getName().getName().toString(), join(p.getDescription()));
                        }
                    }
                    case ReturnTree r -> returns = join(r.getDescription());
                    case ThrowsTree t ->
                        throwsMap.put(resolveExceptionName(trees, methodPath, tree, t), join(t.getDescription()));
                    default -> {}
                }
            }
        }
        doc.put("raw", tree == null ? "" : join(tree.getFullBody()));
        doc.put("summary", tree == null ? "" : join(tree.getFirstSentence()));
        doc.put("params", params);
        if (returns != null) {
            doc.put("returns", returns);
        }
        doc.put("throws", throwsMap);
        return doc;
    }

    /** The thrown type's fully-qualified name when it resolves, its written name otherwise. */
    private static String resolveExceptionName(
            DocTrees trees, TreePath methodPath, DocCommentTree docCommentTree, ThrowsTree throwsTree) {
        DocTreePath path = DocTreePath.getPath(methodPath, docCommentTree, throwsTree.getExceptionName());
        Element resolved = path == null ? null : trees.getElement(path);
        if (resolved instanceof TypeElement type) {
            return type.getQualifiedName().toString();
        }
        return throwsTree.getExceptionName().toString();
    }

    /**
     * Reassembles a doc comment fragment exactly as written: a {@link DocTree} node's default
     * string form re-escapes non-ASCII characters (an em dash comes back as {@code —},
     * indistinguishable from a literal source escape), so each node is read through its own text
     * accessor instead.
     */
    private static String join(List<? extends DocTree> nodes) {
        StringBuilder text = new StringBuilder();
        for (DocTree node : nodes) {
            switch (node) {
                case TextTree t -> text.append(t.getBody());
                case LiteralTree t ->
                    text.append("{@")
                            .append(t.getKind() == DocTree.Kind.CODE ? "code" : "literal")
                            .append(' ')
                            .append(t.getBody().getBody())
                            .append('}');
                case LinkTree t -> {
                    text.append("{@")
                            .append(t.getKind() == DocTree.Kind.LINK ? "link" : "linkplain")
                            .append(' ')
                            .append(t.getReference().getSignature());
                    String label = join(t.getLabel());
                    if (!label.isEmpty()) {
                        text.append(' ').append(label);
                    }
                    text.append('}');
                }
                case StartElementTree t -> text.append('<').append(t.getName()).append(t.isSelfClosing() ? "/>" : ">");
                case EndElementTree t -> text.append("</").append(t.getName()).append('>');
                default -> text.append(node);
            }
        }
        return text.toString();
    }

    private static @Nullable AnnotationMirror annotation(Element element, String qualifiedName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (qualifiedName.equals(((TypeElement) mirror.getAnnotationType().asElement())
                    .getQualifiedName()
                    .toString())) {
                return mirror;
            }
        }
        return null;
    }

    private static boolean isNullable(TypeMirror type) {
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().asElement() instanceof TypeElement annotationType
                    && NULLABLE_ANNOTATION.equals(
                            annotationType.getQualifiedName().toString())) {
                return true;
            }
        }
        return false;
    }

    /** The canonical Java source spelling of a type: qualified names, type arguments, no annotations. */
    private static String formatType(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE ->
                type.getKind().toString().toLowerCase(Locale.ROOT);
            case VOID -> "void";
            case ARRAY -> formatType(((ArrayType) type).getComponentType()) + "[]";
            case DECLARED -> formatDeclared((DeclaredType) type);
            case TYPEVAR -> ((TypeVariable) type).asElement().getSimpleName().toString();
            case WILDCARD -> formatWildcard((WildcardType) type);
            default -> type.toString();
        };
    }

    private static String formatDeclared(DeclaredType type) {
        String base = ((TypeElement) type.asElement()).getQualifiedName().toString();
        List<? extends TypeMirror> arguments = type.getTypeArguments();
        if (arguments.isEmpty()) {
            return base;
        }
        StringBuilder spelling = new StringBuilder(base).append('<');
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                spelling.append(", ");
            }
            spelling.append(formatType(arguments.get(i)));
        }
        return spelling.append('>').toString();
    }

    private static String formatWildcard(WildcardType type) {
        TypeMirror extendsBound = type.getExtendsBound();
        if (extendsBound != null) {
            return "? extends " + formatType(extendsBound);
        }
        TypeMirror superBound = type.getSuperBound();
        if (superBound != null) {
            return "? super " + formatType(superBound);
        }
        return "?";
    }
}
