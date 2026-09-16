package io.github.libtmux;

import java.util.Arrays;
import java.util.List;
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
 * 3.8 that serialized form is either the classic checksummed grammar or JSON; both round-trip
 * exactly, and {@code select-layout} takes either back. The classic checksum is verified here; a
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
     * @throws IllegalArgumentException if tmux would not recognise the name, which on some versions
     *     is not a recoverable error
     */
    public static String require(String layout) {
        if (NAMED.contains(layout) || isSerialized(layout)) {
            return layout;
        }
        throw new IllegalArgumentException(
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
