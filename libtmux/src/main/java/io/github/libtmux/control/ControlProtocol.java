package io.github.libtmux.control;

import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.internal.CommandStrings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Frames control-mode replies and encodes requests for tmux's command parser. */
final class ControlProtocol {

    static final int DEFAULT_MAX_REPLY_BYTES = 16 * 1024 * 1024;
    private static final Pattern GUARD = Pattern.compile("^%(begin|end|error) (-?\\d+) (\\d+) (-?\\d+)$");

    private final int maxReplyBytes;
    private @Nullable Guard opening;
    private List<String> lines = List.of();
    private long replyBytes;

    ControlProtocol() {
        this(DEFAULT_MAX_REPLY_BYTES);
    }

    ControlProtocol(int maxReplyBytes) {
        if (maxReplyBytes < 1) {
            throw new IllegalArgumentException("maxReplyBytes is not positive");
        }
        this.maxReplyBytes = maxReplyBytes;
    }

    sealed interface Result permits Awaiting, Notification, Reply {}

    enum Awaiting implements Result {
        INSTANCE
    }

    record Notification(String line) implements Result {}

    record Reply(OperationOutcome outcome, List<String> lines) implements Result {

        Reply {
            lines = List.copyOf(lines);
        }
    }

    Result accept(String line) {
        return accept(line, line.getBytes(StandardCharsets.UTF_8).length);
    }

    Result accept(String line, int encodedBytes) {
        if (encodedBytes < 0) {
            throw new IllegalArgumentException("encodedBytes is negative");
        }
        Matcher matcher = GUARD.matcher(line);
        if (opening == null) {
            if (matcher.matches() && matcher.group(1).equals("begin")) {
                opening = Guard.from(matcher);
                lines = new ArrayList<>();
                replyBytes = 0;
                return Awaiting.INSTANCE;
            }
            return new Notification(line);
        }

        if (matcher.matches() && opening.matches(matcher)) {
            OperationOutcome outcome =
                    switch (matcher.group(1)) {
                        case "end" -> OperationOutcome.COMPLETE;
                        case "error" -> OperationOutcome.FAILED;
                        default -> null;
                    };
            if (outcome != null) {
                Reply reply = new Reply(outcome, lines);
                opening = null;
                lines = List.of();
                replyBytes = 0;
                return reply;
            }
        }

        long added = (long) encodedBytes + 1;
        if (replyBytes > maxReplyBytes - added) {
            throw new LimitExceeded(maxReplyBytes);
        }
        replyBytes += added;
        lines.add(line);
        return Awaiting.INSTANCE;
    }

    static String line(List<String> argv) {
        return CommandStrings.stringify(argv);
    }

    private record Guard(String time, String number, String flags) {

        static Guard from(Matcher matcher) {
            return new Guard(matcher.group(2), matcher.group(3), matcher.group(4));
        }

        boolean matches(Matcher matcher) {
            return time.equals(matcher.group(2)) && number.equals(matcher.group(3)) && flags.equals(matcher.group(4));
        }
    }

    static final class LimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LimitExceeded(int limit) {
            super("control reply exceeded the " + limit + " byte limit");
        }
    }
}
