package io.github.libtmux.internal;

import io.github.libtmux.Layouts;
import io.github.libtmux.Names;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.internal.support.Capabilities;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Feature boundaries for literal commands and the commands hidden by managed guards. */
public final class TmuxFeatures {
    private TmuxFeatures() {}

    /** Original commands remain separate from their encoded incarnation guard. */
    public record Intent(List<List<String>> commands, boolean repairedBreak, boolean namedBufferCleanup) {
        public Intent {
            commands = commands.stream().map(List::copyOf).toList();
        }

        public static Intent literal(List<List<String>> commands) {
            return new Intent(commands, false, false);
        }
    }

    public static void require(TmuxVersion version, Intent intent) {
        if (intent.namedBufferCleanup() && !Capabilities.BUFFER_DELETE_NAMED.availableOn(version)) {
            throw new UnsupportedFeatureException(
                    "managed paste buffer cleanup", Capabilities.BUFFER_DELETE_NAMED.since(), version);
        }
        for (List<String> command : intent.commands()) {
            if (command.isEmpty()) continue;
            String verb = command.getFirst();
            if (matches(verb, "delete-buffer", "deleteb", 3)) {
                if (flags(command, "b").contains('b') && !Capabilities.BUFFER_DELETE_NAMED.availableOn(version)) {
                    throw new UnsupportedFeatureException(
                            "deleting a buffer by exact name", Capabilities.BUFFER_DELETE_NAMED.since(), version);
                }
            } else if (matches(verb, "set-buffer", "setb", 5)) {
                Set<Character> flags = flags(command, "btn");
                if (flags.contains('b') && flags.contains('n') && !Capabilities.BUFFER_RENAME.availableOn(version)) {
                    throw new UnsupportedFeatureException(
                            "renaming a buffer by exact name", Capabilities.BUFFER_RENAME.since(), version);
                }
            } else if (matches(verb, "split-window", "splitw", 2)) {
                if (Capabilities.SPLIT_WINDOW_PERCENT_SIZE.affects(version)
                        && flags(command, "ceFlpsStRmxyXYL").contains('p')) {
                    throw new UnsupportedFeatureException(
                            "tmux 3.4 split-window -p is unreliable; use -l with a percentage");
                }
            } else if (matches(verb, "break-pane", "breakp", 2)
                    && Capabilities.BREAK_PANE_MISNAMES_WINDOWS.affects(version)
                    && !intent.repairedBreak()) {
                throw new UnsupportedFeatureException(
                        "literal break-pane and unnamed breaks are unreliable on tmux 3.7; use Pane.breakOut(name) or tmux 3.7a+");
            } else if (matches(verb, "new-session", "new", 3)) {
                names(version, command, "cefFnstxy", "ns");
            } else if (matches(verb, "new-window", "neww", 5)) {
                names(version, command, "cenFt", "n");
            } else if (matches(verb, "rename-session", "rename", 8) || matches(verb, "rename-window", "renamew", 8)) {
                renamed(version, command);
            } else if (matches(verb, "select-layout", "selectl", 8)
                    && !Capabilities.LAYOUT_WITHOUT_CHECKSUM.availableOn(version)) {
                Set<Character> flags = new HashSet<>();
                int operand = options(command, "t", flags);
                if (operand < command.size() && !flags.contains('n') && !flags.contains('p') && !flags.contains('E')) {
                    String layout = command.get(operand);
                    // 3.3a ends the server on an operand that is neither a layout name nor
                    // checksummed. A checksummed string is tmux's to accept or report, geometry
                    // included, so only the checksum is checked here.
                    if (!checksummed(layout)) {
                        try {
                            Layouts.require(layout, version);
                            if (flags.contains('o')
                                    && Layouts.builtIn(layout, version).isPresent()) {
                                throw new IllegalArgumentException(
                                        "select-layout -o requires a layout tmux wrote: " + layout);
                            }
                        } catch (IllegalArgumentException invalid) {
                            UnsupportedFeatureException refused = new UnsupportedFeatureException(
                                    "select-layout with a layout without tmux's checksum (" + invalid.getMessage()
                                            + ")",
                                    Capabilities.LAYOUT_WITHOUT_CHECKSUM.since(),
                                    version);
                            refused.addSuppressed(invalid);
                            throw refused;
                        }
                    }
                }
            }
        }
    }

    /** Refuses a name flag's value that the exact release would reject; {@code wanted} lists the flags. */
    private static void names(TmuxVersion version, List<String> command, String valued, String wanted) {
        int index = 1;
        for (; index < command.size(); index++) {
            String word = command.get(index);
            if (word.equals("--") || !word.startsWith("-") || word.equals("-")) break;
            for (int offset = 1; offset < word.length(); offset++) {
                char flag = word.charAt(offset);
                if (valued.indexOf(flag) < 0) continue;
                String value;
                if (offset + 1 < word.length()) {
                    value = word.substring(offset + 1);
                } else if (index + 1 < command.size()) {
                    value = command.get(++index);
                } else {
                    return;
                }
                if (wanted.indexOf(flag) >= 0) Names.require(value, version);
                break;
            }
        }
    }

    /** The new name is the operand after the options of rename-session and rename-window. */
    private static void renamed(TmuxVersion version, List<String> command) {
        Set<Character> ignored = new HashSet<>();
        int operand = options(command, "t", ignored);
        if (operand < command.size()) Names.require(command.get(operand), version);
    }

    /** Whether the string leads with the four-digit checksum tmux computes over the rest. */
    private static boolean checksummed(String layout) {
        if (layout.length() < 6 || layout.charAt(4) != ',') {
            return false;
        }
        int declared;
        try {
            declared = Integer.parseInt(layout.substring(0, 4), 16);
        } catch (NumberFormatException notHex) {
            return false;
        }
        for (int index = 0; index < 4; index++) {
            if (Character.digit(layout.charAt(index), 16) < 0) {
                return false;
            }
        }
        int checksum = 0;
        for (int index = 5; index < layout.length(); index++) {
            checksum = ((checksum >> 1) + ((checksum & 1) << 15) + layout.charAt(index)) & 0xffff;
        }
        return checksum == declared;
    }

    private static boolean matches(String verb, String full, String alias, int minimumPrefix) {
        return verb.equals(alias) || (verb.length() >= minimumPrefix && full.startsWith(verb));
    }

    /** Stops at values and --, so names such as -p or -n are never interpreted as flags. */
    private static Set<Character> flags(List<String> command, String valued) {
        Set<Character> result = new HashSet<>();
        options(command, valued, result);
        return result;
    }

    private static int options(List<String> command, String valued, Set<Character> result) {
        int index = 1;
        for (; index < command.size(); index++) {
            String word = command.get(index);
            if (word.equals("--")) return index + 1;
            if (!word.startsWith("-") || word.equals("-")) break;
            for (int offset = 1; offset < word.length(); offset++) {
                char flag = word.charAt(offset);
                result.add(flag);
                if (valued.indexOf(flag) >= 0) {
                    if (offset + 1 == word.length()) index++;
                    break;
                }
            }
        }
        return index;
    }
}
