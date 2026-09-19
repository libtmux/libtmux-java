package io.github.libtmux.mcp;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this process itself most recently typed into a pane, literally - so a wait started right
 * after can recognise the pane's own echo of it rather than reading it as new output.
 *
 * <p>No capture can say "this line is an echo" on its own; the only party that knows what was
 * typed is whichever call typed it. Key <em>names</em> are not recorded - {@code Enter} is not
 * echoed as the four characters of its name - only literal text, which is.
 *
 * <p>A line is recognised as an echo only when it carries the whole typed text, so an echo the
 * terminal wrapped across rows is not recognised: a wait for a marker in a command longer than the
 * pane is wide can still match the echo. {@code run_shell_command} frames its own command and has
 * no such gap.
 *
 * <p>Held per pane id, briefly, and only in memory: nothing here is a caller-visible cursor or
 * state a client can rely on beyond the one wait it is meant to protect.
 */
final class TypedEcho {

    private static final Duration TTL = Duration.ofSeconds(10);

    private static final ConcurrentHashMap<String, Entry> RECENT = new ConcurrentHashMap<>();

    private TypedEcho() {}

    private record Entry(String text, long recordedAtNanos) {}

    /** Records literal text just sent to a pane, replacing whatever this pane last had. */
    static void record(String paneId, String text) {
        RECENT.put(paneId, new Entry(text, System.nanoTime()));
    }

    /** The text this process typed into this pane, if young enough to still be its echo. */
    static Optional<String> recentFor(String paneId) {
        Entry entry = RECENT.get(paneId);
        if (entry == null) {
            return Optional.empty();
        }
        if (System.nanoTime() - entry.recordedAtNanos() > TTL.toNanos()) {
            RECENT.remove(paneId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.text());
    }
}
