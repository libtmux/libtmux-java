package io.github.libtmux.transport;

import java.util.List;
import java.util.Set;

/**
 * Whether running a request twice leaves tmux as running it once does.
 *
 * <p>Decided from the commands themselves: a request is idempotent only when every command in it
 * reads and changes nothing. Anything this library cannot tell is a read counts as a change, so a
 * failure never reports a mutation as safe to resend.
 */
public enum Idempotence {
    /** Every command only reads; sending it again cannot change tmux. */
    IDEMPOTENT,
    /** At least one command may change tmux, so a second send may apply it twice. */
    NOT_IDEMPOTENT;

    // Full names and their aliases. display-message and capture-pane read only with -p, and are
    // decided below; every other verb, run-shell and if-shell included, is treated as a change.
    private static final Set<String> READS = Set.of(
            "has-session",
            "has",
            "list-buffers",
            "lsb",
            "list-clients",
            "lsc",
            "list-commands",
            "lscm",
            "list-keys",
            "lsk",
            "list-panes",
            "lsp",
            "list-sessions",
            "ls",
            "list-windows",
            "lsw",
            "server-info",
            "info",
            "show-buffer",
            "showb",
            "show-environment",
            "showenv",
            "show-hooks",
            "show-messages",
            "showmsgs",
            "show-options",
            "show",
            "show-prompt-history",
            "showphist",
            "show-window-options",
            "showw");

    /** The idempotence of a request made of {@code commands}, each an argv. */
    public static Idempotence of(List<? extends List<String>> commands) {
        return commands.stream().allMatch(Idempotence::reads) ? IDEMPOTENT : NOT_IDEMPOTENT;
    }

    private static boolean reads(List<String> argv) {
        if (argv.isEmpty()) {
            return false;
        }
        String verb = argv.get(0);
        return switch (verb) {
            case "display-message", "display", "capture-pane", "capturep" -> printsOnly(argv);
            case "if-shell", "if" -> guardedRead(argv);
            default -> READS.contains(verb);
        };
    }

    /** Whether flags ask for printing only: {@code -p}, and for a capture no {@code -b} buffer. */
    private static boolean printsOnly(List<String> argv) {
        boolean printing = false;
        for (String word : argv.subList(1, argv.size())) {
            if (!word.startsWith("-") || word.equals("--")) {
                break;
            }
            if (word.indexOf('b') > 0 && argv.get(0).startsWith("capture")) {
                return false;
            }
            printing |= word.indexOf('p') > 0;
        }
        return printing;
    }

    /**
     * {@code if-shell [-bF] [-t target] condition command [else]} as this library sends it: reads
     * when both branches are single read commands. A branch naming no command at all is how a stale
     * handle is refused, and runs nothing.
     */
    private static boolean guardedRead(List<String> argv) {
        int index = 1;
        while (index < argv.size() && argv.get(index).startsWith("-")) {
            if (argv.get(index).equals("-t")) {
                index++;
            }
            index++;
        }
        List<String> rest = argv.subList(Math.min(index + 1, argv.size()), argv.size());
        return !rest.isEmpty()
                && rest.size() <= 2
                && rest.stream().allMatch(branch -> branch.startsWith("libtmux-stale-") || readsText(branch));
    }

    private static boolean readsText(String command) {
        if (command.indexOf(';') >= 0) {
            return false;
        }
        return reads(List.of(command.strip().split("\\s+")));
    }
}
