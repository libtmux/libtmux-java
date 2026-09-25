package io.github.libtmux.junit5;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A command's flags and the words after them, read as tmux's argument parser reads them. */
record Flags(Map<String, String> values, Set<String> switches, List<String> positional) {

    /**
     * The flags that take a value, per command, since tmux decides that per command: {@code -f}
     * is a value to {@code new-session} and a switch to {@code split-window}, and {@code -b} names
     * a buffer to one and puts a pane before another.
     */
    private static final Map<String, Set<String>> VALUED = Map.ofEntries(
            Map.entry("new-session", Set.of("-t", "-s", "-n", "-c", "-x", "-y", "-F", "-e", "-f")),
            Map.entry("new-window", Set.of("-t", "-n", "-c", "-F", "-e")),
            Map.entry("split-window", Set.of("-t", "-l", "-c", "-F", "-e", "-s", "-S", "-R", "-m")),
            Map.entry("capture-pane", Set.of("-t", "-S", "-E", "-b")),
            Map.entry("select-pane", Set.of("-t", "-T")),
            Map.entry("if-shell", Set.of("-t")),
            Map.entry("set-environment", Set.of("-t")),
            Map.entry("show-environment", Set.of("-t")),
            Map.entry("list-sessions", Set.of("-F", "-f")),
            Map.entry("list-windows", Set.of("-t", "-F", "-f")),
            Map.entry("list-panes", Set.of("-t", "-F", "-f")));

    private static final Set<String> DEFAULT_VALUED = Set.of("-t", "-F", "-b", "-c");

    static Flags parse(List<String> argv) {
        Set<String> valued = VALUED.getOrDefault(argv.getFirst(), DEFAULT_VALUED);
        Map<String, String> values = new HashMap<>();
        java.util.Set<String> switches = new java.util.HashSet<>();
        int index = 1;
        while (index < argv.size()) {
            String word = argv.get(index);
            if (word.equals("--")) {
                index++;
                break;
            }
            if (!word.startsWith("-") || word.length() < 2) {
                break;
            }
            if (valued.contains(word) && index + 1 < argv.size()) {
                values.put(word, argv.get(index + 1));
                index += 2;
            } else {
                for (char flag : word.substring(1).toCharArray()) {
                    switches.add("-" + flag);
                }
                index++;
            }
        }
        return new Flags(values, switches, List.copyOf(argv.subList(index, argv.size())));
    }

    boolean has(String flag) {
        return switches.contains(flag) || values.containsKey(flag);
    }

    @Nullable
    String value(String flag) {
        return values.get(flag);
    }
}
