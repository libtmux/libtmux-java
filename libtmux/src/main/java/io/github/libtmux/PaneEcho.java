package io.github.libtmux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Tracks pending and recently submitted input under each server incarnation and pane id. */
final class PaneEcho {

    private static final Duration RECENT_TTL = Duration.ofSeconds(10);

    private static final Duration ABANDONED_AFTER = Duration.ofHours(1);

    private static final int KEPT_PER_PANE = 32;

    private static final int MAX_PENDING_CHARS = 4_096;

    private record Key(ServerIdentity identity, PaneId pane) {}

    private record Entry(String text, long recordedAtNanos) {}

    private record PaneEchoState(
            List<String> pendingLines,
            boolean pendingCaptured,
            boolean trackable,
            List<Entry> recent,
            long lastTouchedNanos,
            ReentrantLock dispatch) {
        static PaneEchoState fresh(long now) {
            return new PaneEchoState(List.of(), false, true, List.of(), now, new ReentrantLock());
        }
    }

    private static String currentLine(PaneEchoState state) {
        List<String> lines = state.pendingLines();
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
    }

    record Live(List<String> pending, List<String> recent) {
        static final Live NONE = new Live(List.of(), List.of());
    }

    private record Plan(PaneEchoState eager, PaneEchoState confirmed) {}

    private static final List<String> SUBMIT_KEYS = List.of("C-m", "Enter", "KPEnter");
    private static final List<String> KILL_KEYS = List.of("C-c", "C-u");
    private static final List<String> ERASE_KEYS = List.of("BSpace", "C-h");
    private static final List<String> NOOP_KEYS = List.of("DC");

    private static final Pattern UNHANDLED_KEY_NAME = Pattern.compile(
            "^(?:BTab|Down|End|Escape|F(?:[1-9]|1[0-2])|Home|IC|Left|NPage|PPage|PageDown|PageUp" + "|Right|Tab|Up)$");

    private static final Pattern CONTROL_OR_META_COMBO = Pattern.compile("^(?:C|M|S)(?:-(?:C|M|S))*-.");

    private enum Kind {
        SUBMIT,
        KILL,
        ERASE,
        NOOP,
        UNKNOWN,
        TEXT
    }

    private record KeyEffect(Kind kind, String text) {
        static final KeyEffect SUBMIT = new KeyEffect(Kind.SUBMIT, "");
        static final KeyEffect KILL = new KeyEffect(Kind.KILL, "");
        static final KeyEffect ERASE = new KeyEffect(Kind.ERASE, "");
        static final KeyEffect NOOP = new KeyEffect(Kind.NOOP, "");
        static final KeyEffect UNKNOWN = new KeyEffect(Kind.UNKNOWN, "");

        static KeyEffect text(String text) {
            return new KeyEffect(Kind.TEXT, text);
        }
    }

    private static KeyEffect classifyKey(String key) {
        if (SUBMIT_KEYS.contains(key)) {
            return KeyEffect.SUBMIT;
        }
        if (KILL_KEYS.contains(key)) {
            return KeyEffect.KILL;
        }
        if (ERASE_KEYS.contains(key)) {
            return KeyEffect.ERASE;
        }
        if (NOOP_KEYS.contains(key)) {
            return KeyEffect.NOOP;
        }
        if (key.equals("Space")) {
            return KeyEffect.text(" ");
        }
        if (key.codePointCount(0, key.length()) == 1) {
            return KeyEffect.text(key);
        }
        if (UNHANDLED_KEY_NAME.matcher(key).matches()
                || CONTROL_OR_META_COMBO.matcher(key).find()) {
            return KeyEffect.UNKNOWN;
        }
        return KeyEffect.text(key);
    }

    private static String dropLastCodePoint(String text) {
        if (text.isEmpty()) {
            return text;
        }
        int cut = text.offsetByCodePoints(text.length(), -1);
        return text.substring(0, cut);
    }

    private static List<Entry> pushRecent(List<Entry> recent, String text, long now) {
        if (text.isEmpty() || text.length() > MAX_PENDING_CHARS) {
            return recent;
        }
        List<Entry> kept = new ArrayList<>(recent);

        if (!kept.isEmpty() && kept.get(kept.size() - 1).text().equals(text)) {
            kept.set(kept.size() - 1, new Entry(text, now));
        } else {
            kept.add(new Entry(text, now));
        }
        return List.copyOf(kept.subList(Math.max(0, kept.size() - KEPT_PER_PANE), kept.size()));
    }

    private static Plan planKeys(PaneEchoState current, List<String> keys, long now) {
        List<KeyEffect> effects = new ArrayList<>();
        for (String key : keys) {
            KeyEffect effect = classifyKey(key);
            if (effect.kind() == Kind.TEXT) {
                effects.addAll(literalEffects(effect.text()));
            } else {
                effects.add(effect);
            }
        }
        return planEffects(current, effects, now);
    }

    private static Plan planEffects(PaneEchoState current, List<KeyEffect> effects, long now) {
        String line = currentLine(current);
        boolean captured = current.pendingCaptured();
        boolean trackable = current.trackable();
        List<Entry> recent = current.recent();
        List<String> settling = new ArrayList<>();
        for (KeyEffect effect : effects) {
            switch (effect.kind()) {
                case TEXT -> {
                    String grown = trackable ? line + effect.text() : "";
                    trackable &= grown.length() <= MAX_PENDING_CHARS;

                    line = grown.length() > MAX_PENDING_CHARS ? "" : grown;
                    captured = false;
                }
                case NOOP -> {}
                case UNKNOWN -> {
                    trackable = false;

                    line = "";
                    captured = false;
                }
                case ERASE -> {
                    // Shell redraws can leave the pre-erase text visible; retain it once per edit run.

                    if (!captured) {
                        if (!line.isEmpty()) {
                            settling.add(line);
                        }
                        recent = pushRecent(recent, line, now);
                    }
                    captured = true;

                    line = dropLastCodePoint(line);
                }
                case SUBMIT, KILL -> {
                    trackable = true;
                    if (!line.isEmpty()) {
                        settling.add(line);
                    }
                    recent = pushRecent(recent, line, now);
                    line = "";
                    captured = false;
                }
            }
        }
        PaneEchoState confirmed = new PaneEchoState(
                line.isEmpty() ? List.of() : List.of(line), captured, trackable, recent, now, current.dispatch());
        if (settling.isEmpty()) {
            return new Plan(confirmed, confirmed);
        }
        List<String> eagerLines =
                new ArrayList<>(settling.subList(Math.max(0, settling.size() - KEPT_PER_PANE), settling.size()));
        if (!line.isEmpty()) {
            eagerLines.add(line);
        }
        PaneEchoState eager = new PaneEchoState(
                List.copyOf(eagerLines),
                current.pendingCaptured(),
                trackable,
                current.recent(),
                now,
                current.dispatch());
        return new Plan(eager, confirmed);
    }

    private static Plan planLiteral(PaneEchoState current, String text, long now) {
        return text.isEmpty() ? new Plan(current, current) : planEffects(current, literalEffects(text), now);
    }

    private static List<KeyEffect> literalEffects(String text) {
        List<KeyEffect> effects = new ArrayList<>();
        int from = 0;
        for (int at = 0; at < text.length(); at++) {
            char character = text.charAt(at);
            KeyEffect effect =
                    switch (character) {
                        case '\r', '\n' -> KeyEffect.SUBMIT;
                        case '\b', '\u007f' -> KeyEffect.ERASE;
                        case '\u0003', '\u0015' -> KeyEffect.KILL;
                        default -> Character.isISOControl(character) ? KeyEffect.UNKNOWN : null;
                    };
            if (effect != null) {
                if (at > from) {
                    effects.add(KeyEffect.text(text.substring(from, at)));
                }
                effects.add(effect);
                from = at + 1;
            }
        }
        if (from < text.length()) {
            effects.add(KeyEffect.text(text.substring(from)));
        }
        return effects;
    }

    private final Map<Key, PaneEchoState> states = new ConcurrentHashMap<>();

    private final LongSupplier nanoTime;

    PaneEcho() {
        this(System::nanoTime);
    }

    PaneEcho(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    final class Recorded {
        private final Key key;
        private final @Nullable PaneEchoState before;
        private final PaneEchoState eager;
        private final PaneEchoState confirmed;
        private boolean settled;

        private Recorded(Key key, @Nullable PaneEchoState before, PaneEchoState eager, PaneEchoState confirmed) {
            this.key = key;
            this.before = before;
            this.eager = eager;
            this.confirmed = confirmed;
        }

        // Only confirmed submissions enter a wait's persistent discount set.
        void confirm() {
            if (settled) {
                return;
            }
            settled = true;
            try {
                long now = nanoTime.getAsLong();
                List<Entry> recent = confirmed.recent().stream()
                        .map(entry -> entry.recordedAtNanos() == confirmed.lastTouchedNanos()
                                ? new Entry(entry.text(), now)
                                : entry)
                        .toList();
                states.replace(
                        key,
                        eager,
                        new PaneEchoState(
                                confirmed.pendingLines(),
                                confirmed.pendingCaptured(),
                                confirmed.trackable(),
                                recent,
                                now,
                                confirmed.dispatch()));
            } finally {
                eager.dispatch().unlock();
            }
        }

        // An older transaction must not overwrite a newer dispatch's state.
        void rollback() {
            if (settled) {
                return;
            }
            settled = true;
            try {
                if (before == null) {
                    states.remove(key, eager);
                } else {
                    states.replace(key, eager, before);
                }
            } finally {
                eager.dispatch().unlock();
            }
        }
    }

    private Recorded mutate(ServerIdentity identity, PaneId pane, PlannedMutation planner) {
        long now = nanoTime.getAsLong();
        sweep(now);
        Key key = new Key(identity, pane);
        while (true) {
            PaneEchoState observed = states.computeIfAbsent(key, ignored -> PaneEchoState.fresh(now));
            // Serialize dispatches to one pane without blocking captures or unrelated panes.
            observed.dispatch().lock();
            PaneEchoState current = states.get(key);
            if (current == null || !current.dispatch().equals(observed.dispatch())) {
                observed.dispatch().unlock();
                continue;
            }
            try {
                Plan plan = planner.plan(current, nanoTime.getAsLong());
                states.put(key, plan.eager());
                PaneEchoState before = current.trackable()
                                && current.pendingLines().isEmpty()
                                && current.recent().isEmpty()
                        ? null
                        : current;
                return new Recorded(key, before, plan.eager(), plan.confirmed());
            } catch (RuntimeException | Error failure) {
                observed.dispatch().unlock();
                throw failure;
            }
        }
    }

    private interface PlannedMutation {
        Plan plan(PaneEchoState current, long now);
    }

    private void sweep(long now) {
        states.forEach((key, state) -> {
            boolean expired = now - state.lastTouchedNanos() > ABANDONED_AFTER.toNanos()
                    || (state.trackable()
                            && state.pendingLines().isEmpty()
                            && state.recent().stream()
                                    .allMatch(entry -> now - entry.recordedAtNanos() > RECENT_TTL.toNanos()));
            if (expired && !state.dispatch().isLocked() && state.dispatch().tryLock()) {
                try {
                    if (!state.dispatch().hasQueuedThreads()) {
                        states.remove(key, state);
                    }
                } finally {
                    state.dispatch().unlock();
                }
            }
        });
    }

    Recorded recordLiteral(ServerIdentity identity, PaneId pane, String text) {
        return mutate(identity, pane, (state, now) -> planLiteral(state, text, now));
    }

    Recorded recordKeys(ServerIdentity identity, PaneId pane, List<String> keys) {
        return mutate(identity, pane, (state, now) -> planKeys(state, keys, now));
    }

    int size() {
        return states.size();
    }

    void forget(ServerIdentity identity, PaneId pane) {
        states.remove(new Key(identity, pane));
    }

    Live liveFor(ServerIdentity identity, PaneId pane) {
        long now = nanoTime.getAsLong();
        sweep(now);
        PaneEchoState state = states.get(new Key(identity, pane));
        if (state == null) {
            return Live.NONE;
        }
        List<String> fresh = state.recent().stream()
                .filter(entry -> now - entry.recordedAtNanos() <= RECENT_TTL.toNanos())
                .map(Entry::text)
                .toList();
        return new Live(state.pendingLines(), fresh);
    }

    static List<String> withoutEcho(List<String> lines, List<String> echoes) {
        List<String> shown = lines;
        for (String echo : echoes.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList()) {
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
