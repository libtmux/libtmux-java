package io.github.libtmux;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Which layout names are safe to hand to tmux.
 *
 * <p>Public because every path that can reach select-layout needs it, not only the one that reads
 * a workspace file. This check exists because an unrecognised layout name does not produce an error on every tmux:
 * on 3.3a it crashes the server, taking down every session on that socket, including ones this
 * program never created. A workspace file is user-supplied text, so the name has to be checked
 * before it is dispatched rather than after tmux has had it.
 *
 * <p>tmux accepts either one of its named layouts or a serialized layout tmux itself produced. Since
 * 3.8 that serialized form is either the classic checksummed grammar or JSON, and {@code
 * select-layout} takes either back - but only JSON round-trips exactly. The classic grammar
 * carries no pane identity, only shape, so which process lands in which cell can differ from
 * where it started; see {@link Window#applyLayout}. The classic checksum is verified here; a
 * four-hex-digit prefix alone does not make the rest safe for tmux to parse. JSON carries no such
 * signature, so a JSON-shaped string is only trusted once the running tmux is new enough to have
 * written it - see {@link #requireSerialized}.
 */
public final class Layouts {

    private static final int MAX_SERIALIZED_LENGTH = 8_191;
    private static final int MAX_DEPTH = 256;
    private static final List<String> NAMED =
            Arrays.stream(Layout.values()).map(Layout::tmuxName).toList();

    /**
     * tmux 3.8 added the JSON (v2) layout format: {@code layout_dump} in {@code layout-custom.c}
     * writes {@code {"V":2,"L":...}} instead of the checksummed string from that release on. Landed
     * at {@code bf43fdc0} in tmux's own history, which {@code git tag --contains} places on {@code
     * 3.8-rc} and nothing earlier. Every release before it has no JSON parser at all: {@code
     * layout_construct} dispatches purely on whether the input opens with a curly brace, and
     * anything that branch cannot parse ends the server outright on 3.3a. A JSON-shaped string is
     * therefore only something *this* tmux could have written once it is at least this version.
     */
    private static final TmuxVersion JSON_LAYOUT_SINCE = new TmuxVersion(3, 8, "");

    private Layouts() {}

    /**
     * Returns the layout unchanged, having checked tmux will recognise it.
     *
     * <p>Accepts the classic checksummed form, a JSON layout (by shape only — a running version is
     * needed to say whether this server could have written it, which {@link #require(String,
     * TmuxVersion)} does), a built-in name, or an unambiguous prefix of exactly one built-in name
     * ({@code layout_set_lookup} is a prefix match, so {@code tile} and {@code even-h} apply on
     * every tmux release). Ambiguity is judged against every name this library knows, not only the
     * ones the eventual server will have — the version to narrow that with is not available here.
     *
     * @throws IllegalArgumentException if tmux would not recognise the name, which on some versions
     *     is not a recoverable error
     */
    public static String require(String layout) {
        if (isSerialized(layout) || isJsonShaped(layout)) {
            return layout;
        }
        List<Layout> candidates = resolve(layout, List.of(Layout.values()));
        if (candidates.size() == 1) {
            return layout;
        }
        if (candidates.size() > 1) {
            // tmux does know this prefix - a narrower running version might make it unique, but
            // nothing here says it does not know it at all, which is what "not a tmux layout" claims.
            throw ambiguous(layout, candidates);
        }
        throw unknown(layout);
    }

    /**
     * Refuses a layout tmux would not recognise, and a built-in name or JSON layout the running
     * tmux predates.
     *
     * <p>An unknown name reaches {@code layout_parse} exactly as a malformed string does, so a
     * mirrored preset on a release older than 3.5 ends the server on 3.3a rather than being
     * refused. The version is read lazily, because a chain is built before it runs.
     *
     * <p>A prefix is resolved against only the names {@code running} actually has: {@code
     * layout_set_lookup} is compiled from a fixed table, so {@code main-h} is unambiguous on a
     * release before 3.5 (no mirrored variant exists to collide with) and ambiguous from 3.5 on —
     * confirmed against the matrix. Resolving against every name this library knows regardless of
     * version would refuse a prefix the running tmux itself accepts.
     *
     * @throws IllegalArgumentException if tmux would not recognise the layout
     * @throws UnsupportedTmuxVersionException if the name or the JSON format arrived after this
     *     release
     */
    public static String require(String layout, TmuxVersion running) {
        if (isJsonShaped(layout)) {
            if (!running.atLeast(JSON_LAYOUT_SINCE)) {
                throw new UnsupportedTmuxVersionException("a JSON layout", JSON_LAYOUT_SINCE, running);
            }
            return layout;
        }
        if (isSerialized(layout)) {
            return layout;
        }
        List<Layout> candidates = resolve(layout, supported(running));
        if (candidates.size() == 1) {
            candidates.getFirst().requireSupported(running);
            return layout;
        }
        if (candidates.size() > 1) {
            throw ambiguous(layout, candidates);
        }
        throw unknown(layout);
    }

    /**
     * The built-in layout a name or an unambiguous prefix of one denotes, resolved against what
     * {@code running} actually supports — the same resolution {@link #require(String, TmuxVersion)}
     * applies, exposed so a caller that must choose between the enum path and a raw string (a
     * workspace file's own dispatch) makes the same choice the guard just validated.
     */
    public static Optional<Layout> builtIn(String layout, TmuxVersion running) {
        List<Layout> candidates = resolve(layout, supported(running));
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private static List<Layout> supported(TmuxVersion running) {
        return Arrays.stream(Layout.values())
                .filter(candidate -> running.atLeast(candidate.since()))
                .toList();
    }

    /** An exact match against every name this library knows, or else an unambiguous prefix of one in {@code pool}. */
    private static List<Layout> resolve(String layout, List<Layout> pool) {
        for (Layout candidate : Layout.values()) {
            if (candidate.tmuxName().equals(layout)) {
                return List.of(candidate);
            }
        }
        return prefixed(layout, pool);
    }

    /** Every candidate {@code layout} is a prefix of. Empty text prefixes everything, so it is refused up front. */
    private static List<Layout> prefixed(String layout, List<Layout> candidates) {
        if (layout.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> candidate.tmuxName().startsWith(layout))
                .toList();
    }

    private static IllegalArgumentException ambiguous(String layout, List<Layout> candidates) {
        return new IllegalArgumentException("ambiguous layout prefix: '" + layout + "'; matches "
                + candidates.stream().map(Layout::tmuxName).toList());
    }

    private static IllegalArgumentException unknown(String layout) {
        return new IllegalArgumentException(
                "not a tmux layout: '" + layout + "'; expected one of " + NAMED + " or a serialized layout");
    }

    /**
     * Requires an exact layout string rather than a built-in layout name.
     *
     * <p>The classic form answers "did tmux write this?" by its own checksum, without needing to
     * know what tmux is running. JSON carries nothing equivalent, so the only question left to ask
     * is "could this tmux have written it?" - which is a version, not a parse. Recognising the shape
     * is as far as this goes: a JSON body that passes the shape check but is not real is tmux's own
     * problem to refuse, and it does, safely, on every release that understands the format at all
     * (confirmed against next-3.9: malformed JSON answers an ordinary parse error, never a crash).
     *
     * @throws IllegalArgumentException if the string is not a layout tmux wrote
     * @throws UnsupportedTmuxVersionException if it is JSON-shaped but {@code running} predates the
     *     format, so it cannot be one this server wrote
     */
    static String requireSerialized(String layout, TmuxVersion running) {
        if (isSerialized(layout)) {
            return layout;
        }
        if (isJsonShaped(layout)) {
            if (!running.atLeast(JSON_LAYOUT_SINCE)) {
                throw new UnsupportedTmuxVersionException("a JSON layout", JSON_LAYOUT_SINCE, running);
            }
            return layout;
        }
        throw new IllegalArgumentException("not a layout tmux wrote: " + layout);
    }

    /**
     * Whether {@code layout} has the shape of tmux's JSON (v2) format - not whether it is one.
     *
     * <p>Mirrors {@code layout_construct}'s own dispatch in {@code layout-custom.c}: tmux decides
     * between the two grammars on nothing more than whether the first non-blank byte is an opening
     * curly brace. Matching that exactly, rather than inspecting the body, is deliberate - the
     * layout is an opaque token tmux hands back and forth, and parsing its structure is tmux's job.
     * A leading dash fails this the same way it fails {@link #isSerialized}, so it still falls
     * through to the refusal below rather than reaching {@code select-layout} as a flag.
     */
    private static boolean isJsonShaped(String layout) {
        String trimmed = layout.strip();
        return trimmed.length() >= 2 && trimmed.charAt(0) == '{' && trimmed.charAt(trimmed.length() - 1) == '}';
    }

    private static boolean isSerialized(String layout) {
        if (layout.length() < 6 || layout.charAt(4) != ',') {
            return false;
        }
        for (int index = 0; index < 4; index++) {
            char digit = layout.charAt(index);
            if (!((digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f') || (digit >= 'A' && digit <= 'F'))) {
                return false;
            }
        }
        int declared;
        try {
            declared = Integer.parseInt(layout.substring(0, 4), 16);
        } catch (NumberFormatException notHex) {
            return false;
        }
        int checksum = 0;
        for (int index = 5; index < layout.length(); index++) {
            checksum = ((checksum >> 1) + ((checksum & 1) << 15)) & 0xffff;
            checksum = (checksum + layout.charAt(index)) & 0xffff;
        }
        String body = layout.substring(5);
        return checksum == declared && body.length() <= MAX_SERIALIZED_LENGTH && new Serialized(body).valid();
    }

    /** The subset parsed by tmux's layout_construct, with layout_check's size invariants. */
    private static final class Serialized {

        private final String value;
        private int at;

        Serialized(String value) {
            this.value = value;
        }

        boolean valid() {
            return cell(0) != null && at == value.length();
        }

        private @Nullable Cell cell(int depth) {
            if (depth > MAX_DEPTH) {
                return null;
            }
            int width = number('x');
            int height = number(',');
            int x = number(',');
            int y = unsigned();
            if (width < 1 || height < 1 || x < 0 || y < 0) {
                return null;
            }
            skipPaneId();
            if (at == value.length() || delimiter(value.charAt(at))) {
                return new Cell(width, height);
            }

            char open = value.charAt(at++);
            char close;
            if (open == '{') {
                close = '}';
            } else if (open == '[') {
                close = ']';
            } else {
                return null;
            }

            List<Cell> children = new java.util.ArrayList<>();
            @Nullable Cell child = cell(depth + 1);
            if (child == null) {
                return null;
            }
            children.add(child);
            while (at < value.length() && value.charAt(at) == ',') {
                at++;
                child = cell(depth + 1);
                if (child == null) {
                    return null;
                }
                children.add(child);
            }
            if (at >= value.length() || value.charAt(at++) != close) {
                return null;
            }
            return fits(open, width, height, children) ? new Cell(width, height) : null;
        }

        private int number(char terminator) {
            int number = unsigned();
            if (number < 0 || at >= value.length() || value.charAt(at++) != terminator) {
                return -1;
            }
            return number;
        }

        private int unsigned() {
            int start = at;
            long number = 0;
            while (at < value.length() && value.charAt(at) >= '0' && value.charAt(at) <= '9') {
                number = number * 10 + value.charAt(at++) - '0';
                if (number > Integer.MAX_VALUE) {
                    return -1;
                }
            }
            return at == start ? -1 : (int) number;
        }

        /** A comma followed by another width belongs to the parent, not to this cell's pane id. */
        private void skipPaneId() {
            if (at >= value.length() || value.charAt(at) != ',') {
                return;
            }
            int saved = at++;
            int id = unsigned();
            if (id < 0 || (at < value.length() && value.charAt(at) == 'x')) {
                at = saved;
            }
        }

        private static boolean delimiter(char character) {
            return character == ',' || character == '}' || character == ']';
        }

        private static boolean fits(char open, int width, int height, List<Cell> children) {
            long total = children.size() - 1L;
            if (open == '{') {
                for (Cell child : children) {
                    if (child.height() != height) {
                        return false;
                    }
                    total += child.width();
                }
                return total == width;
            }
            for (Cell child : children) {
                if (child.width() != width) {
                    return false;
                }
                total += child.height();
            }
            return total == height;
        }
    }

    private record Cell(int width, int height) {}
}
