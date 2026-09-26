package io.github.libtmux.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Scans every public type the library exports and fails when one that classifies at least one
 * method with {@link Operation} leaves another public method unclassified; separately checks that
 * {@code operation-catalog.json}, the Doclet's build-time extraction of the same annotations, agrees
 * with what is loaded at runtime.
 *
 * <p>A type is examined only once it carries at least one {@link Operation} method; a pure value
 * type, a spec, or a builder that never touches tmux declares none and is never examined. Once a
 * type qualifies, every public method it declares needs {@link Operation}, with two exemptions:
 * a method overriding {@link Object#equals}, {@link Object#hashCode} or {@link Object#toString},
 * and a synthetic or bridge method the compiler generated. A static factory gets neither exemption
 * — {@link Server#open} and its siblings classify like any instance method. A type carrying
 * {@link Advanced} must classify nothing at all, on any of its methods.
 *
 * <p>Guards against vacuity: the scan must have found and classified {@link Server}, {@link
 * io.github.libtmux.Session}, {@link io.github.libtmux.Window}, {@link io.github.libtmux.Pane},
 * {@link io.github.libtmux.Client}, and {@link io.github.libtmux.control.ControlClient}, so an
 * empty or misdirected scan cannot pass by finding nothing to check.
 */
final class OperationCatalogTest {

    private static final Set<String> REQUIRED_HANDLES = Set.of(
            "io.github.libtmux.Server",
            "io.github.libtmux.Session",
            "io.github.libtmux.Window",
            "io.github.libtmux.Pane",
            "io.github.libtmux.Client",
            "io.github.libtmux.control.ControlClient");

    @Test
    void everyOperationTypeClassifiesEveryPublicMethod() throws IOException, URISyntaxException {
        List<String> violations = new ArrayList<>();
        Set<String> qualifying = new HashSet<>();

        for (Class<?> type : publicTypes()) {
            scan(type, violations, qualifying);
        }

        assertEquals(List.of(), violations, "public methods without @Operation");
        for (String required : REQUIRED_HANDLES) {
            assertTrue(qualifying.contains(required), required + " was never found classified");
        }
    }

    /**
     * Fails when {@code operation-catalog.json} (the Doclet's source-level extraction, packaged at
     * {@code META-INF/io.github.libtmux/operation-catalog.json}) and the {@link Operation}
     * annotations loaded by reflection disagree: a method missing on either side, or present on
     * both with a different {@link Operation#value()} or {@link Operation#tmuxSince()}, fails this.
     *
     * <p>The comparison key is the owner, method name, parameter count and varargs-ness — not the
     * parameter types themselves, since a declared type erases under reflection but not in the
     * Doclet's source-level, generic-aware spelling. Two overloads that collide on this key still
     * catch a real disagreement: any swap changes which key holds which {@code kind}/{@code
     * tmuxSince}, and the two multisets compared below stop matching.
     */
    @Test
    void jsonCatalogMatchesRuntimeAnnotations() throws IOException, URISyntaxException {
        Map<String, Integer> fromReflection = new TreeMap<>();
        for (Class<?> type : publicTypes()) {
            for (Method method : type.getDeclaredMethods()) {
                Operation operation = method.getAnnotation(Operation.class);
                if (operation == null) {
                    continue;
                }
                String key = key(
                        type.getName(),
                        method.getName(),
                        method.getParameterCount(),
                        method.isVarArgs(),
                        operation.value().name(),
                        operation.tmuxSince());
                fromReflection.merge(key, 1, Integer::sum);
            }
        }

        String json;
        try (InputStream in =
                OperationCatalogTest.class.getResourceAsStream("/META-INF/io.github.libtmux/operation-catalog.json")) {
            if (in == null) {
                throw new AssertionError("operation-catalog.json is not on the test classpath");
            }
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Map<String, Integer> fromJson = new TreeMap<>();
        Map<?, ?> root = (Map<?, ?>) MinimalJson.parse(json);
        for (Object entry : listField(root, "operations")) {
            Map<?, ?> operation = (Map<?, ?>) entry;
            String key = key(
                    stringField(operation, "owner"),
                    stringField(operation, "name"),
                    listField(operation, "parameters").size(),
                    booleanField(operation, "varargs"),
                    stringField(operation, "kind"),
                    stringField(operation, "tmuxSince"));
            fromJson.merge(key, 1, Integer::sum);
        }

        assertEquals(fromReflection, fromJson);
    }

    private static String key(
            String owner, String name, int parameterCount, boolean varargs, String kind, String tmuxSince) {
        return owner + '#' + name + '/' + parameterCount + (varargs ? "..." : "") + " -> " + kind + '@' + tmuxSince;
    }

    private static String stringField(Map<?, ?> record, String field) {
        return (String) Objects.requireNonNull(record.get(field), field);
    }

    private static List<?> listField(Map<?, ?> record, String field) {
        return (List<?>) Objects.requireNonNull(record.get(field), field);
    }

    private static boolean booleanField(Map<?, ?> record, String field) {
        return (Boolean) Objects.requireNonNull(record.get(field), field);
    }

    /** Every public type under {@link Server}'s classpath root, excluding {@code internal}, anonymous, and local types. */
    private static List<Class<?>> publicTypes() throws IOException, URISyntaxException {
        Path classes = Path.of(
                Server.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> types = new ArrayList<>();

        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.toString().contains("/internal/"))
                    .sorted()
                    .toList()) {
                String name = className(classes, file);
                if (name == null) {
                    continue;
                }
                Class<?> type;
                try {
                    type = Class.forName(name, false, OperationCatalogTest.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError unreachable) {
                    continue;
                }
                if (Modifier.isPublic(type.getModifiers()) && !type.isAnonymousClass() && !type.isLocalClass()) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    /** Checks one loaded type, recording either a violation per unclassified method or its name once it qualifies. */
    private static void scan(Class<?> type, List<String> violations, Set<String> qualifying) {
        Method[] declared = type.getDeclaredMethods();
        boolean advanced = type.isAnnotationPresent(Advanced.class);
        if (advanced) {
            for (Method method : declared) {
                if (method.isAnnotationPresent(Operation.class)) {
                    violations.add(type.getName() + "." + method.getName() + " is both @Advanced and @Operation");
                }
            }
            return;
        }
        boolean hasOperation = false;
        for (Method method : declared) {
            hasOperation |= method.isAnnotationPresent(Operation.class);
        }
        if (!hasOperation) {
            return;
        }
        qualifying.add(type.getName());
        for (Method method : declared) {
            if (!Modifier.isPublic(method.getModifiers())
                    || method.isSynthetic()
                    || method.isBridge()
                    || overridesObjectMethod(method)
                    || method.isAnnotationPresent(Operation.class)) {
                continue;
            }
            violations.add(type.getName() + "." + method.getName());
        }
    }

    private static boolean overridesObjectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException notObject) {
            return false;
        }
    }

    /** The binary class name for a {@code .class} file, or null for one this scan does not classify. */
    private static @Nullable String className(Path root, Path file) {
        String relative = root.relativize(file).toString().replace('/', '.').replace('\\', '.');
        if (!relative.endsWith(".class") || relative.equals("module-info.class")) {
            return null;
        }
        relative = relative.substring(0, relative.length() - ".class".length());
        if (relative.endsWith(".package-info")) {
            return null;
        }
        String simple = relative.substring(relative.lastIndexOf('.') + 1);
        int lastDollar = simple.lastIndexOf('$');
        if (lastDollar >= 0 && simple.substring(lastDollar + 1).chars().allMatch(Character::isDigit)) {
            return null; // anonymous or local class
        }
        return relative;
    }

    /**
     * Parses the tiny, well-formed subset of JSON the catalog Doclet writes: this test's own
     * output, never third-party input, so a null literal or a non-finite number is unsupported
     * rather than handled.
     */
    private static final class MinimalJson {

        private final String text;
        private int pos;

        private MinimalJson(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            MinimalJson parser = new MinimalJson(text);
            Object value = parser.readValue();
            parser.skipWhitespace();
            if (parser.pos != text.length()) {
                throw new IllegalArgumentException("trailing content at " + parser.pos);
            }
            return value;
        }

        private Object readValue() {
            skipWhitespace();
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> {
                    pos += "true".length();
                    yield true;
                }
                case 'f' -> {
                    pos += "false".length();
                    yield false;
                }
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                pos++; // :
                map.put(key, readValue());
                skipWhitespace();
                if (text.charAt(pos++) == '}') {
                    break;
                }
            }
            return map;
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (text.charAt(pos++) == ']') {
                    break;
                }
            }
            return list;
        }

        private String readString() {
            pos++; // opening quote
            StringBuilder value = new StringBuilder();
            while (true) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        value.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("unsupported escape \\" + escape);
                }
            }
            return value.toString();
        }

        private Long readNumber() {
            int start = pos;
            while (pos < text.length() && "-+0123456789".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            return Long.parseLong(text.substring(start, pos));
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
