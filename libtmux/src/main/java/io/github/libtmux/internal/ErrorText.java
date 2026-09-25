package io.github.libtmux.internal;

import io.github.libtmux.transport.OperationReport;
import java.util.List;
import java.util.stream.Collectors;

/** What tmux printed on its error stream, as it appears in a failure's message. */
public final class ErrorText {

    private ErrorText() {}

    /**
     * {@code ": "} and the non-blank lines joined by {@code "; "}, or nothing when tmux printed none.
     *
     * <p>Capped at {@link OperationReport#ERROR_LIMIT} characters: a message lands in every log line
     * that prints the failure, and the failure's own {@code errorLines()} keeps the text whole.
     */
    public static String suffix(List<String> stderr) {
        String reported = stderr.stream().filter(line -> !line.isBlank()).collect(Collectors.joining("; "));
        if (reported.isEmpty()) {
            return "";
        }
        return ": "
                + (reported.length() <= OperationReport.ERROR_LIMIT
                        ? reported
                        : reported.substring(0, OperationReport.ERROR_LIMIT) + "…");
    }
}
