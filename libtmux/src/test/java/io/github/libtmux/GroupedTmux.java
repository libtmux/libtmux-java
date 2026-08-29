package io.github.libtmux;

import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of tmux a snapshot double has to be: a process-identity fence and a command group.
 *
 * <p>A capture arrives as one fenced group, so a double answering one listing at a time would never
 * see the request the library actually sends. This runs the group the way tmux does — in order,
 * stopping at the first failure — and refuses it when the fence names a different server, so a test
 * exercises the fence rather than assuming it.
 */
final class GroupedTmux {

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");
    private static final Pattern FENCED_PID = Pattern.compile("#\\{==:#\\{pid},(\\d+)}");

    private GroupedTmux() {}

    /**
     * Answers one request, running any group it carries.
     *
     * @param livePid the server this double is pretending to be
     * @param command answers one command, as tmux would
     */
    static CommandResult execute(CommandRequest request, long livePid, Function<List<String>, CommandResult> command) {
        List<String> argv = request.commands().get(0);
        if (!argv.get(0).equals("if-shell")) {
            return command.apply(argv);
        }
        Matcher fence = FENCED_PID.matcher(argv.get(2));
        String stale = argv.get(argv.size() - 1);
        if (fence.find() && Long.parseLong(fence.group(1)) != livePid) {
            return new CommandResult(1, List.of(), List.of("unknown command: " + stale));
        }
        return group(argv.get(argv.size() - 2), command);
    }

    /** Runs each command in turn, and discards the rest once one fails, which is what tmux does. */
    private static CommandResult group(String commands, Function<List<String>, CommandResult> command) {
        List<String> stdout = new ArrayList<>();
        for (String one : commands.split(" ; ", -1)) {
            List<String> words = new ArrayList<>();
            Matcher word = QUOTED.matcher(one);
            while (word.find()) {
                words.add(word.group(1));
            }
            if (words.isEmpty()) {
                continue;
            }
            CommandResult answered = words.get(0).equals("display-message") && words.size() == 3
                    ? new CommandResult(0, List.of(words.get(2)), List.of())
                    : command.apply(words);
            stdout.addAll(answered.stdout());
            if (!answered.succeeded()) {
                return new CommandResult(answered.exitCode(), stdout, answered.stderr());
            }
        }
        return new CommandResult(0, stdout, List.of());
    }
}
