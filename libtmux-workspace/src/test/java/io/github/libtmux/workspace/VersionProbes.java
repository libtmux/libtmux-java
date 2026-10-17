package io.github.libtmux.workspace;

import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import java.util.List;

/**
 * The version probe as a test double sees it, and tmux's answer to it.
 *
 * <p>The probe is {@code run-shell -C '<marker>#{version}'}: the daemon expands the format, fails to
 * parse the result as a command, and spells the version in {@code unknown command: <marker><version>}.
 */
final class VersionProbes {

    private static final String MARKER = "libtmux-version-";
    private static final String FORMAT = "#{version}";

    private VersionProbes() {}

    /** Whether this request is the version probe and nothing else. */
    static boolean is(CommandRequest request) {
        return request.commands().size() == 1 && is(request.commands().getFirst());
    }

    static boolean is(List<String> command) {
        return command.size() == 3
                && command.get(0).equals("run-shell")
                && command.get(1).equals("-C")
                && command.get(2).startsWith(MARKER)
                && command.get(2).endsWith(FORMAT);
    }

    /** The reply of a daemon that reports this version. */
    static CommandResult reply(CommandRequest request, String version) {
        String argument = request.commands().getFirst().get(2);
        String marker = argument.substring(0, argument.length() - FORMAT.length());
        return new CommandResult(1, List.of(), List.of("unknown command: " + marker + version));
    }
}
