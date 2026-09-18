package io.github.libtmux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this library last typed into each pane, so a wait can tell the pane's answer from its own
 * question.
 *
 * <p>A terminal echoes what is typed at it. A caller that sends a command and then waits for
 * something the command's own text contains — {@code sendLine("./build --target release")} followed
 * by a wait for {@code release} — would otherwise be answered by the echo, immediately, before the
 * command has done anything. No capture can recognise an echo on its own: the only party that knows
 * what was typed is whoever typed it, which is why this is recorded rather than detected.
 *
 * <p>Only literal text is recorded. A key name is not echoed as its own letters — {@code Enter}
 * presses a key rather than typing five characters — so {@link Pane#send} and {@link Pane#sendKeys}
 * record nothing.
 *
 * <p>Held per server rather than globally, and briefly: a wait started long after the typing is
 * watching for something else by then.
 */
final class PaneEcho {

    /**
     * Long enough to cover the gap between typing and waiting, short enough that a pane reused for
     * something else is not still suppressing text that only looks like an old echo.
     */
    private static final Duration TTL = Duration.ofSeconds(10);

    private final Map<PaneId, Entry> recent = new ConcurrentHashMap<>();

    private record Entry(String text, long recordedAtNanos) {}

    /** Records literal text just sent to a pane, replacing whatever that pane last had. */
    void record(PaneId pane, String text) {
        if (!text.isEmpty()) {
            recent.put(pane, new Entry(text, System.nanoTime()));
        }
    }

    /** The text this library typed into the pane, while it is young enough to still be on screen. */
    Optional<String> recentFor(PaneId pane) {
        Entry entry = recent.get(pane);
        if (entry == null) {
            return Optional.empty();
        }
        if (System.nanoTime() - entry.recordedAtNanos() > TTL.toNanos()) {
            recent.remove(pane, entry);
            return Optional.empty();
        }
        return Optional.of(entry.text());
    }

    /** The pane's lines with any echo of {@code typed} taken out of them. */
    List<String> withoutEcho(List<String> lines, PaneId pane) {
        return recentFor(pane).map(echo -> withoutEcho(lines, echo)).orElse(lines);
    }

    /**
     * Removes the echo's characters rather than every row it touches.
     *
     * <p>A row that holds the whole echo is simply dropped. When the terminal broke the echo across
     * rows, dropping them all would take real output with it: a shell writes its next line starting
     * on the row the wrapped echo ended on. So the echo's own characters are taken out where they
     * fall, and whatever shared those rows stays.
     */
    static List<String> withoutEcho(List<String> lines, String echo) {
        if (echo.isEmpty() || lines.isEmpty()) {
            return lines;
        }
        if (lines.stream().anyMatch(line -> line.contains(echo))) {
            return lines.stream().filter(line -> !line.contains(echo)).toList();
        }
        int[] lineStart = new int[lines.size() + 1];
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            lineStart[index] = joined.length();
            joined.append(lines.get(index));
        }
        lineStart[lines.size()] = joined.length();
        String text = joined.toString();
        if (!text.contains(echo)) {
            return lines;
        }
        boolean[] echoed = new boolean[text.length()];
        int from = 0;
        int at;
        while ((at = text.indexOf(echo, from)) >= 0) {
            int end = at + echo.length();
            for (int position = at; position < end; position++) {
                echoed[position] = true;
            }
            from = end;
        }
        List<String> kept = new ArrayList<>(lines.size());
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            row.setLength(0);
            for (int position = lineStart[index]; position < lineStart[index + 1]; position++) {
                if (!echoed[position]) {
                    row.append(text.charAt(position));
                }
            }
            if (!row.isEmpty() || lineStart[index] == lineStart[index + 1]) {
                kept.add(row.toString());
            }
        }
        return kept;
    }
}
