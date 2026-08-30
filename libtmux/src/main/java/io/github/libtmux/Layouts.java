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
 * <p>tmux accepts either one of its named layouts or a serialized layout carrying tmux's own
 * checksum. The checksum is verified here; a four-hex-digit prefix alone does not make the rest
 * safe for tmux to parse.
 */
public final class Layouts {

    private static final int MAX_SERIALIZED_LENGTH = 8_191;
    private static final int MAX_DEPTH = 256;
    private static final List<String> NAMED =
            Arrays.stream(Layout.values()).map(Layout::tmuxName).toList();

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

    /** Requires an exact layout string rather than a built-in layout name. */
    static String requireSerialized(String layout) {
        if (isSerialized(layout)) {
            return layout;
        }
        throw new IllegalArgumentException("not a layout tmux wrote: " + layout);
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
