package io.github.libtmux.batch;

import io.github.libtmux.format.Tokens;
import io.github.libtmux.transport.CommandResult;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Several tmux commands in one invocation, each with an outcome of its own.
 *
 * <p>tmux runs a group until a command fails and then discards the rest, so the number of replies
 * cannot say which command failed: an error in the first position and an error in the last both
 * come back as one nonzero exit. Counting replies is wrong in every position but one.
 *
 * <p>A marker is therefore interleaved after each operation. Markers are discarded along with
 * everything else after a failure, so the last marker seen is exactly the last operation tmux ran,
 * and the operations either side of that boundary are known rather than guessed.
 */
public final class Batch {

    private static final String MARKER = Tokens.perProcess();

    private final Function<List<String>, CommandResult> dispatch;
    private final List<List<String>> operations = new ArrayList<>();

    /**
     * Collects operations to run together.
     *
     * @param dispatch runs the assembled command group and returns tmux's raw reply
     */
    public Batch(Function<List<String>, CommandResult> dispatch) {
        this.dispatch = dispatch;
    }

    /** Adds one tmux command, its arguments already separate elements. */
    public Batch add(String... argv) {
        return add(List.of(argv));
    }

    /** Adds one tmux command. */
    public Batch add(List<String> argv) {
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("an operation has no command");
        }
        operations.add(List.copyOf(argv));
        return this;
    }

    /** How many operations have been collected. */
    public int size() {
        return operations.size();
    }

    /**
     * Runs every collected operation in one tmux invocation.
     *
     * @return one result per operation, in submission order
     */
    public BatchResult run() {
        if (operations.isEmpty()) {
            return new BatchResult(List.of());
        }
        CommandResult reply = dispatch.apply(assemble());
        return attribute(reply);
    }

    /** {@code op0 ; marker0 ; op1 ; marker1 ; …}, with each {@code ;} its own argv element. */
    private List<String> assemble() {
        List<String> argv = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            if (index > 0) {
                argv.add(";");
            }
            argv.addAll(operations.get(index));
            argv.add(";");
            argv.addAll(List.of("display-message", "-p", marker(index)));
        }
        return argv;
    }

    private static String marker(int index) {
        return MARKER + ":" + index;
    }

    private BatchResult attribute(CommandResult reply) {
        List<OperationResult> results = new ArrayList<>(operations.size());
        List<String> pending = new ArrayList<>();
        int completed = 0;
        for (String line : reply.stdout()) {
            if (completed < operations.size() && line.equals(marker(completed))) {
                results.add(
                        new OperationResult(operations.get(completed), OperationOutcome.COMPLETE, pending, List.of()));
                pending = new ArrayList<>();
                completed++;
            } else {
                pending.add(line);
            }
        }
        if (completed == operations.size()) {
            if (!reply.succeeded()) {
                // Every marker arrived and tmux still reported failure, so the last operation's
                // effect is not something this can vouch for.
                int last = results.size() - 1;
                OperationResult previous = results.get(last);
                results.set(
                        last,
                        new OperationResult(
                                previous.argv(), OperationOutcome.UNKNOWN, previous.stdout(), reply.stderr()));
            }
            return new BatchResult(results);
        }
        // The first operation without its marker is the one that failed; tmux discarded the rest.
        results.add(new OperationResult(operations.get(completed), OperationOutcome.FAILED, pending, reply.stderr()));
        for (int index = completed + 1; index < operations.size(); index++) {
            results.add(new OperationResult(operations.get(index), OperationOutcome.SKIPPED, List.of(), List.of()));
        }
        return new BatchResult(results);
    }
}
