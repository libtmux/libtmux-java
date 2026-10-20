package io.github.libtmux.internal.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Layout;
import io.github.libtmux.Layouts;
import io.github.libtmux.Names;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.internal.ControlAcquisition;
import io.github.libtmux.internal.TmuxFeatures;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The registry is the one table the gates and the published manifest both read, so these tests hold
 * the manifest to the registry, the registry to its own rules, and each gate to the registry.
 */
final class CapabilitiesTest {
    private static final String WRITE = "LIBTMUX_WRITE_CAPABILITIES";
    private static final Pattern KEBAB = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final List<String> PACKAGES =
            List.of("io.github.libtmux.", "io.github.libtmux.control.", "io.github.libtmux.exception.", "java.lang.");

    /** The root {@code capabilities.json}; set {@value #WRITE} to 1 to rewrite it from the registry. */
    @Test
    void theCommittedManifestIsTheRegistry() throws IOException {
        Path manifest = root().resolve("capabilities.json");
        if ("1".equals(System.getenv(WRITE))) {
            Files.writeString(manifest, Capabilities.manifest(), StandardCharsets.UTF_8);
        }
        assertEquals(
                Capabilities.manifest(),
                Files.readString(manifest, StandardCharsets.UTF_8),
                manifest + " differs from the registry; run with " + WRITE + "=1 to regenerate it");
    }

    @Test
    void everyIdIsKebabCaseAndAppearsOnce() {
        Set<String> seen = new HashSet<>();
        List<String> ids = new ArrayList<>();
        Capabilities.capabilities().forEach(capability -> ids.add(capability.id()));
        Capabilities.workarounds().forEach(workaround -> ids.add(workaround.id()));
        for (String id : ids) {
            assertTrue(KEBAB.matcher(id).matches(), id);
            assertTrue(seen.add(id), id + " appears twice");
        }
    }

    @Test
    void everyNamedApiAndErrorIsAPublicSymbol() {
        List<String> names = new ArrayList<>();
        Capabilities.capabilities().forEach(capability -> {
            names.addAll(capability.apis());
            if (capability.error() != null) names.add(capability.error());
        });
        Capabilities.workarounds().forEach(workaround -> names.addAll(workaround.apis()));
        for (String name : names) {
            assertTrue(resolves(name), name + " is not a public type or method");
        }
    }

    @Test
    void aCapabilityIsAvailableFromItsReleaseAndNotBefore() {
        for (Capabilities.Capability capability : Capabilities.capabilities()) {
            assertTrue(capability.availableOn(capability.since()), capability.id());
            assertTrue(capability.availableOn(TmuxVersion.parse("next-9.9")), capability.id());
            assertFalse(
                    capability.availableOn(
                            TmuxVersion.parse("next-" + capability.since().major() + "."
                                    + capability.since().minor())),
                    capability.id());
        }
    }

    @Test
    void aWorkaroundAffectsItsListedReleasesAndNotTheNextOne() {
        for (Capabilities.Workaround workaround : Capabilities.workarounds()) {
            for (TmuxVersion release : workaround.releases()) {
                assertTrue(workaround.affects(release), workaround.id() + " " + release);
            }
            assertFalse(workaround.affects(TmuxVersion.parse("9.9")), workaround.id());
            if (workaround.until() != null) {
                assertFalse(workaround.affects(workaround.until()), workaround.id());
            }
        }
    }

    @Test
    void theFloorIsTheMinimumTheVersionCheckEnforces() throws IOException {
        String json = Capabilities.manifest();
        assertTrue(json.contains("\"minimum\": \"" + TmuxVersion.MINIMUM + "\""), json);
    }

    @Test
    void controlIsRefusedBelowTheRegistryReleaseAndAllowedFromIt() {
        TmuxVersion since = Capabilities.CONTROL_MODE.since();
        assertTrue(ControlAcquisition.supports(since));
        assertFalse(ControlAcquisition.supports(TmuxVersion.parse("3.6b")));
        assertThrows(
                UnsupportedFeatureException.class,
                () -> ControlAcquisition.requireSupported(TmuxVersion.parse("3.6b")));
    }

    @Test
    void namedBufferCommandsAreRefusedBelowTheirReleaseOnly() {
        TmuxVersion before = TmuxVersion.parse("3.3a");
        List<List<String>> deletes = List.of(List.of("delete-buffer", "-b", "x"));
        List<List<String>> renames = List.of(List.of("set-buffer", "-b", "x", "-n", "y"));
        assertThrows(
                UnsupportedFeatureException.class,
                () -> TmuxFeatures.require(before, TmuxFeatures.Intent.literal(deletes)));
        assertThrows(
                UnsupportedFeatureException.class,
                () -> TmuxFeatures.require(before, TmuxFeatures.Intent.literal(renames)));
        TmuxFeatures.require(Capabilities.BUFFER_DELETE_NAMED.since(), TmuxFeatures.Intent.literal(deletes));
        TmuxFeatures.require(Capabilities.BUFFER_RENAME.since(), TmuxFeatures.Intent.literal(renames));
    }

    @Test
    void literalPercentSplitAndLiteralBreakAreRefusedOnTheirExactReleases() {
        List<List<String>> split = List.of(List.of("split-window", "-p", "30"));
        List<List<String>> breakOut = List.of(List.of("break-pane", "-s", "%1"));
        TmuxVersion splitRelease =
                Capabilities.SPLIT_WINDOW_PERCENT_SIZE.releases().getFirst();
        TmuxVersion breakRelease =
                Capabilities.BREAK_PANE_MISNAMES_WINDOWS.releases().getFirst();
        assertThrows(
                UnsupportedFeatureException.class,
                () -> TmuxFeatures.require(splitRelease, TmuxFeatures.Intent.literal(split)));
        assertThrows(
                UnsupportedFeatureException.class,
                () -> TmuxFeatures.require(breakRelease, TmuxFeatures.Intent.literal(breakOut)));
        TmuxFeatures.require(TmuxVersion.parse("3.5"), TmuxFeatures.Intent.literal(split));
        TmuxFeatures.require(TmuxVersion.parse("3.7a"), TmuxFeatures.Intent.literal(breakOut));
    }

    @Test
    void layoutsAndNamesFollowTheirRegistryReleases() {
        assertThrows(
                UnsupportedFeatureException.class,
                () -> Layout.MAIN_VERTICAL_MIRRORED.requireSupported(TmuxVersion.parse("3.4")));
        Layout.MAIN_VERTICAL_MIRRORED.requireSupported(Capabilities.LAYOUT_MIRRORED_MAIN.since());
        assertThrows(UnsupportedFeatureException.class, () -> Layouts.require("{\"V\":2}", TmuxVersion.parse("3.7c")));
        assertEquals("{\"V\":2}", Layouts.require("{\"V\":2}", Capabilities.LAYOUT_JSON.since()));
        assertThrows(
                IllegalArgumentException.class,
                () -> Names.require(
                        "a:b", Capabilities.NAME_SEPARATORS_REJECTED.releases().getFirst()));
    }

    /** Whether {@code Type.member} (or {@code Outer.Inner.member}, or a bare type) is public. */
    private static boolean resolves(String name) {
        String[] parts = name.split("\\.", -1);
        Optional<Class<?>> type = Optional.empty();
        int used = 0;
        for (String prefix : PACKAGES) {
            try {
                type = Optional.of(Class.forName(prefix + parts[0]));
                used = 1;
                break;
            } catch (ClassNotFoundException notHere) {
                // Try the next package.
            }
        }
        if (type.isEmpty()) return false;
        Class<?> current = type.get();
        while (used < parts.length) {
            String part = parts[used++];
            Optional<Class<?>> nested = Optional.empty();
            for (Class<?> candidate : current.getClasses()) {
                if (candidate.getSimpleName().equals(part)) nested = Optional.of(candidate);
            }
            if (nested.isPresent()) {
                current = nested.get();
                continue;
            }
            if (used != parts.length) return false;
            for (Method method : current.getMethods()) {
                if (method.getName().equals(part) && Modifier.isPublic(method.getModifiers())) return true;
            }
            return false;
        }
        return Modifier.isPublic(current.getModifiers());
    }

    private static Path root() {
        Path directory = Path.of("").toAbsolutePath();
        for (Path candidate = directory; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("no settings.gradle.kts above " + directory);
    }
}
