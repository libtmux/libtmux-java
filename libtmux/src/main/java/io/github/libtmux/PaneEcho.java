package io.github.libtmux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

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
 * <p>Several texts are kept per pane, not one. A caller that types a command and then presses Enter
 * makes two calls, and remembering only the latest would forget the command and leave the echo
 * matchable again.
 *
 * <p>Held per server rather than globally, and briefly: a wait started long after the typing is
 * watching for something else by then. {@link TypedText} is how a watcher reads this — once, when it
 * starts — and why reading it once is the whole point.
 */
final class PaneEcho {

    /**
     * Long enough to cover the gap between typing and a wait starting, short enough that a pane
     * reused for something else is not still suppressing text that only looks like an old echo.
     */
    private static final Duration TTL = Duration.ofSeconds(10);

    /** A command and the keys that submit it, with room to spare; the oldest is dropped. */
    private static final int KEPT_PER_PANE = 4;

    private final Map<PaneId, List<Entry>> recent = new ConcurrentHashMap<>();

    private final LongSupplier nanoTime;

    PaneEcho() {
        this(System::nanoTime);
    }

    /** Takes its clock, so a gate can age a record out without waiting for it. */
    PaneEcho(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    private record Entry(String text, long recordedAtNanos) {}

    /** Records literal text just sent to a pane, keeping what that pane had. */
    void record(PaneId pane, String text) {
        if (text.isEmpty()) {
            return;
        }
        long now = nanoTime.getAsLong();
        Entry entry = new Entry(text, now);
        // Aged records go when anything is recorded, not only when their own pane is waited on: a
        // long-lived server types into many panes it never waits on, and each would otherwise keep
        // its last few lines here for as long as the server lives.
        recent.values().removeIf(held -> held.stream().allMatch(old -> now - old.recordedAtNanos() > TTL.toNanos()));
        recent.compute(pane, (id, held) -> {
            List<Entry> kept = new ArrayList<>(held == null ? List.of() : held);
            // The same text twice is one record, freshened: a second copy would change nothing but
            // the age of what is kept, and would push an older, different echo out of the list.
            if (!kept.isEmpty() && kept.get(kept.size() - 1).text().equals(text)) {
                kept.set(kept.size() - 1, entry);
            } else {
                kept.add(entry);
            }
            return List.copyOf(kept.subList(Math.max(0, kept.size() - KEPT_PER_PANE), kept.size()));
        });
    }

    /** How many panes hold a record, for a gate on what is kept. */
    int size() {
        return recent.size();
    }

    /** The texts this library typed into the pane that are young enough to still be on screen. */
    List<String> recentFor(PaneId pane) {
        List<Entry> held = recent.get(pane);
        if (held == null) {
            return List.of();
        }
        long now = nanoTime.getAsLong();
        List<String> young = held.stream()
                .filter(entry -> now - entry.recordedAtNanos() <= TTL.toNanos())
                .map(Entry::text)
                .toList();
        if (young.isEmpty()) {
            recent.remove(pane, held);
        }
        return young;
    }

    /**
     * Removes each echo from the pane's lines, wherever it stands as a whole.
     *
     * <p>An occurrence is taken out only when it is not part of a longer word on either side. That is
     * what tells the question from the answer when the answer merely contains the question: {@code id}
     * comes off {@code $ id} and stays inside {@code uid=1000}, and {@code ls} comes off {@code $ ls}
     * and stays inside {@code tools}.
     *
     * <p>Only a word boundary, and not "ends its line", because a shell draws its own things straight
     * after what was typed: zsh puts {@code %} there, and a right-hand prompt follows it on the same
     * row. A rule that insisted on the end of the line let those echoes through to answer the wait,
     * which is the one failure this exists to prevent. The cost is that output which repeats the
     * typed text as a word of its own loses that word — {@code make: ***} becomes {@code : ***} after
     * typing {@code make} — and its answer, {@code Stop.}, is still there.
     *
     * <p>Every such occurrence, not only the first, because a shell can draw the typed line more than
     * once — a prompt that redraws itself on submit shows it twice — and a rule that took one copy
     * left the other to answer the wait. What this cannot do is tell an echo from output that repeats
     * what was typed; a wait for exactly that is not a wait a screen can answer.
     *
     * <p>The lines are scanned as one string so that an echo the terminal broke across rows is still
     * found, with the row boundaries kept so "the end of its line" means what it says.
     */
    static List<String> withoutEcho(List<String> lines, List<String> echoes) {
        List<String> shown = lines;
        for (String echo : echoes) {
            shown = withoutEcho(shown, echo);
        }
        return shown;
    }

    static List<String> withoutEcho(List<String> lines, String echo) {
        if (echo.isEmpty() || lines.isEmpty()) {
            return lines;
        }
        int[] lineStart = new int[lines.size() + 1];
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            lineStart[index] = joined.length();
            joined.append(lines.get(index));
        }
        lineStart[lines.size()] = joined.length();
        String text = joined.toString();
        boolean[] echoed = new boolean[text.length()];
        boolean any = false;
        int from = 0;
        int at;
        while ((at = text.indexOf(echo, from)) >= 0) {
            int end = at + echo.length();
            if (standsAsAnEcho(text, at, end, echo, lineStart)) {
                for (int position = at; position < end; position++) {
                    echoed[position] = true;
                }
                any = true;
                from = end;
            } else {
                from = at + 1;
            }
        }
        if (!any) {
            return lines;
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
            kept.add(row.toString());
        }
        return kept;
    }

    /** Whether this occurrence is not part of a longer word on either side. */
    private static boolean standsAsAnEcho(String text, int at, int end, String echo, int[] lineStart) {
        boolean opens = at == 0
                || startsARow(lineStart, at)
                || !isWordCharacter(text.charAt(at - 1))
                || !isWordCharacter(echo.charAt(0));
        boolean closes = end == text.length()
                || startsARow(lineStart, end)
                || !isWordCharacter(text.charAt(end))
                || !isWordCharacter(echo.charAt(echo.length() - 1));
        return opens && closes;
    }

    private static boolean startsARow(int[] lineStart, int position) {
        return Arrays.binarySearch(lineStart, position) >= 0;
    }

    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }
}
