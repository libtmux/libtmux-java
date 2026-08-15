package io.github.libtmux.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The guard that replaces a code generator.
 *
 * <p>Hand-written metamodels are small, explicit domain code and cost nothing at build time, but they
 * can drift: a duplicated identifier, a field declared with the wrong kind, or an accessor that quietly
 * bypasses canonical minting. Those are exactly the mistakes a generator would have made impossible,
 * so they are asserted here instead — which attacks the real downside of handwriting without adding a
 * compiler plugin, an incremental-build story, or generated sources to debug.
 *
 * <p>Reflection is deliberate. A hand-maintained list of expected handles would drift in the same way
 * the metamodel does, so the check reads what the class actually declares.
 */
public final class MetamodelConformance {

    private MetamodelConformance() {}

    /** One declared handle: the method that exposes it and what it turned out to be. */
    public record Handle(String method, String fieldId, FieldKind kind, boolean canonical, int arity) {}

    /**
     * Asserts the metamodel is internally consistent and covers exactly {@code expectedFieldIds}.
     *
     * @param relationsTakeGraph true when this entity does not hold its own relations, so a relation
     *     handle must accept the graph it navigates rather than being a bare static
     */
    public static void assertConformant(Class<?> metamodel, Set<String> expectedFieldIds, boolean relationsTakeGraph) {
        List<Handle> scalars = new ArrayList<>();
        List<Method> relations = new ArrayList<>();

        for (Method method : metamodel.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            Class<?> returned = method.getReturnType();
            if (returned == Fields.ToManyRef.class || returned == Fields.ToOneRef.class) {
                relations.add(method);
                continue;
            }
            FieldKind expected = kindOf(returned);
            if (expected == null) {
                continue;
            }
            if (method.getParameterCount() != 0) {
                fail("scalar handle " + method.getName() + " must be a bare static, not take arguments");
            }
            FieldRef<?, ?> ref = refOf(metamodel, method, returned);
            scalars.add(new Handle(
                    method.getName(), ref.id(), ref.kind(), ref.provenance().lowerable(), 0));
            assertEquals(
                    expected,
                    ref.kind(),
                    "handle " + method.getName() + " returns a " + returned.getSimpleName()
                            + " but its field is kinded " + ref.kind());
            assertTrue(
                    ref.provenance().lowerable(),
                    "handle " + method.getName() + " is not canonical; it must be minted through "
                            + "EntityMetamodel or no backend may trust its name");
        }

        Map<String, String> byId = new LinkedHashMap<>();
        for (Handle handle : scalars) {
            String previous = byId.put(handle.fieldId(), handle.method());
            if (previous != null) {
                fail("duplicate field id " + handle.fieldId() + " on " + previous + " and " + handle.method());
            }
        }
        assertEquals(
                new TreeSet<>(expectedFieldIds),
                new TreeSet<>(byId.keySet()),
                "declared field coverage drifted from what this entity is supposed to expose");

        for (Method relation : relations) {
            if (relationsTakeGraph) {
                assertEquals(
                        1,
                        relation.getParameterCount(),
                        "relation handle " + relation.getName() + " must take the graph it navigates; a "
                                + "captured entity holds no reference back to its own snapshot");
            } else {
                assertEquals(
                        0,
                        relation.getParameterCount(),
                        "relation handle " + relation.getName()
                                + " should be a bare static when the entity holds its relation");
            }
        }
    }

    @org.jspecify.annotations.Nullable
    private static FieldKind kindOf(Class<?> returned) {
        if (returned == Fields.TextField.class) {
            return FieldKind.TEXT;
        }
        if (returned == Fields.NumberField.class) {
            return FieldKind.NUMBER;
        }
        if (returned == Fields.FlagField.class) {
            return FieldKind.FLAG;
        }
        return null;
    }

    private static FieldRef<?, ?> refOf(Class<?> metamodel, Method method, Class<?> returned) {
        try {
            method.setAccessible(true);
            Object handle = method.invoke(null);
            Method ref = returned.getMethod("ref");
            return (FieldRef<?, ?>) ref.invoke(handle);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException(
                    "could not read handle " + metamodel.getSimpleName() + "." + method.getName(), e);
        }
    }
}
