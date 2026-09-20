package libtmux.internal.loadguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bootstrap-visible checkpoint used only by the namespace-load test agent. */
public final class LoadGuard {
    private static final List<String> violations = new ArrayList<>();
    private static Map<String, Integer> coverage = Map.of();
    private static boolean armed;

    private LoadGuard() {}

    public static synchronized void install(Map<String, Integer> intercepted) {
        coverage = Map.copyOf(intercepted);
    }

    public static synchronized void begin() {
        if (coverage.isEmpty() || armed) {
            throw new IllegalStateException("namespace load guard is unavailable or already armed");
        }
        violations.clear();
        armed = true;
    }

    public static synchronized List<String> end() {
        armed = false;
        return List.copyOf(violations);
    }

    public static synchronized Map<String, Integer> coverage() {
        return coverage;
    }

    public static synchronized void check(String kind, String site) {
        if (armed) {
            String violation = kind + ":" + site;
            violations.add(violation);
            throw new IllegalStateException("NAMESPACE_LOAD_EFFECT: " + violation);
        }
    }
}
