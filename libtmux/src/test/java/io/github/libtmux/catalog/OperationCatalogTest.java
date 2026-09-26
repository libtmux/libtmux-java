package io.github.libtmux.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Scans every public type the library exports and fails when one that classifies at least one
 * method with {@link Operation} leaves another public method unclassified.
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
        Path classes = Path.of(
                Server.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> violations = new ArrayList<>();
        Set<String> qualifying = new HashSet<>();

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
                if (!Modifier.isPublic(type.getModifiers()) || type.isAnonymousClass() || type.isLocalClass()) {
                    continue;
                }
                scan(type, violations, qualifying);
            }
        }

        assertEquals(List.of(), violations, "public methods without @Operation");
        for (String required : REQUIRED_HANDLES) {
            assertTrue(qualifying.contains(required), required + " was never found classified");
        }
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
}
