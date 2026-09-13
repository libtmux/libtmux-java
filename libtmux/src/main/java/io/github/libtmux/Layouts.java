package io.github.libtmux;

import io.github.libtmux.transport.CommandResult;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Validates layout names, checksums and tree structure before dispatch.
 *
 * <p>Unknown names and empty layout trees can crash tmux. Geometry correction and pruning remain
 * tmux's responsibility; a structurally valid layout may still be rejected when applied.
 */
public final class Layouts {

    private static final int MAX_DEPTH = 256;
    private static final List<String> NAMED =
            Arrays.stream(Layout.values()).map(Layout::tmuxName).toList();

    private Layouts() {}

    /**
     * Returns a structurally valid layout unchanged, accepting names from any supported release.
     *
     * @throws IllegalArgumentException if the name or serialized tree is invalid
     */
    public static String require(String layout) {
        if (named(layout, new TmuxVersion(3, 4, "")).isPresent()
                || named(layout, new TmuxVersion(3, 5, "")).isPresent()
                || leaves(layout) > 0) {
            return layout;
        }
        throw new IllegalArgumentException(
                "not a tmux layout: '" + layout + "'; expected one of " + NAMED + " or a serialized layout");
    }

    /**
     * Validates a layout for a window and resolves a built-in abbreviation to its full name.
     *
     * <p>Version-sensitive names use the running daemon, or the selected client only when no
     * daemon is listening. Call this before creating windows or running setup scripts.
     *
     * @param layout a built-in name, unique abbreviation or serialized tree
     * @param server the endpoint which will apply the layout
     * @param paneCount the number of panes which the layout must accommodate
     * @return a full built-in name or the unchanged serialized layout
     * @throws IllegalArgumentException if the layout is invalid or has too few pane cells
     * @throws UnsupportedTmuxVersion if an exact built-in name requires a newer daemon
     * @throws LibTmuxException if the daemon or client version cannot be read
     */
    public static String require(String layout, Server server, int paneCount) {
        if (paneCount < 1) throw new IllegalArgumentException("pane count must be positive");
        require(layout);
        int cells = leaves(layout);
        if (cells > 0) {
            if (cells < paneCount) throw new IllegalArgumentException("layout has fewer cells than panes: " + layout);
            return layout;
        }
        Optional<Layout> before = named(layout, new TmuxVersion(3, 4, ""));
        Optional<Layout> after = named(layout, new TmuxVersion(3, 5, ""));
        if (before.equals(after)) return before.orElseThrow().tmuxName();
        TmuxVersion running = version(server);
        for (Layout candidate : Layout.values()) {
            if (candidate.tmuxName().equals(layout)) candidate.requireSupported(running);
        }
        return named(layout, running)
                .orElseThrow(
                        () -> new IllegalArgumentException("not a unique layout for tmux " + running + ": " + layout))
                .tmuxName();
    }

    private static Optional<Layout> named(String value, TmuxVersion version) {
        if (value.isEmpty()) return Optional.empty();
        List<Layout> available = Arrays.stream(Layout.values())
                .filter(layout -> version.atLeast(layout.since()))
                .toList();
        for (Layout layout : available) {
            if (layout.tmuxName().equals(value)) return Optional.of(layout);
        }
        List<Layout> matches = available.stream()
                .filter(layout -> layout.tmuxName().startsWith(value))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static TmuxVersion version(Server server) {
        CommandResult reply = server.cmd("display-message", "-p", "#{version}");
        if (reply.succeeded()) return parsedVersion(reply, false);
        String reason = String.join("\n", reply.stderr()).strip();
        if (!(reason.startsWith("no server running on ")
                || (reason.startsWith("error connecting to ") && reason.endsWith(" (No such file or directory)")))) {
            throw new LibTmuxException("tmux display-message failed: " + reason);
        }
        return parsedVersion(server.cmd("-V"), true);
    }

    private static TmuxVersion parsedVersion(CommandResult reply, boolean client) {
        if (!reply.succeeded())
            throw new LibTmuxException("could not read tmux version: " + String.join("; ", reply.stderr()));
        String value = String.join("\n", reply.stdout()).strip();
        if (client && value.startsWith("tmux ")) value = value.substring(5);
        try {
            return TmuxVersion.parse(value);
        } catch (IllegalArgumentException invalid) {
            throw new LibTmuxException("could not read tmux version", invalid);
        }
    }

    /** Requires an exact layout string rather than a built-in layout name. */
    static String requireSerialized(String layout) {
        if (leaves(layout) > 0) {
            return layout;
        }
        throw new IllegalArgumentException("not a layout tmux wrote: " + layout);
    }

    private static int leaves(String layout) {
        if (layout.length() < 6 || layout.charAt(4) != ',') {
            return -1;
        }
        for (int index = 0; index < 4; index++) {
            char digit = layout.charAt(index);
            if (!((digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f') || (digit >= 'A' && digit <= 'F'))) {
                return -1;
            }
        }
        int declared;
        try {
            declared = Integer.parseInt(layout.substring(0, 4), 16);
        } catch (NumberFormatException notHex) {
            return -1;
        }
        int checksum = 0;
        for (int index = 5; index < layout.length(); index++) {
            checksum = ((checksum >> 1) + ((checksum & 1) << 15)) & 0xffff;
            checksum = (checksum + layout.charAt(index)) & 0xffff;
        }
        String body = layout.substring(5);
        if (checksum != declared) return -1;
        Serialized parser = new Serialized(body);
        return parser.valid() ? parser.leaves : -1;
    }

    /** Parses tmux's layout_construct grammar without duplicating geometry or pruning. */
    private static final class Serialized {

        private final String value;
        private int at;
        private int leaves;

        Serialized(String value) {
            this.value = value;
        }

        boolean valid() {
            return cell(0) && at == value.length();
        }

        private boolean cell(int depth) {
            if (depth > MAX_DEPTH) return false;
            if (number('x') < 0 || number(',') < 0 || number(',') < 0 || unsigned() < 0) return false;
            skipPaneId();
            if (at == value.length() || delimiter(value.charAt(at))) {
                leaves++;
                return true;
            }
            char open = value.charAt(at++);
            char close;
            if (open == '{') close = '}';
            else if (open == '[') close = ']';
            else return false;
            if (!cell(depth + 1)) return false;
            while (at < value.length() && value.charAt(at) == ',') {
                at++;
                if (!cell(depth + 1)) return false;
            }
            return at < value.length() && value.charAt(at++) == close;
        }

        private long number(char terminator) {
            long number = unsigned();
            if (number < 0 || at >= value.length() || value.charAt(at++) != terminator) {
                return -1;
            }
            return number;
        }

        private long unsigned() {
            int start = at;
            long number = 0;
            while (at < value.length() && value.charAt(at) >= '0' && value.charAt(at) <= '9') {
                number = number * 10 + value.charAt(at++) - '0';
                if (number > 0xffff_ffffL) {
                    return -1;
                }
            }
            return at == start ? -1 : number;
        }

        /** A comma followed by another width belongs to the parent, not to this cell's pane id. */
        private void skipPaneId() {
            if (at >= value.length() || value.charAt(at) != ',') {
                return;
            }
            int saved = at++;
            long id = unsigned();
            if (id < 0 || (at < value.length() && value.charAt(at) == 'x')) {
                at = saved;
            }
        }

        private static boolean delimiter(char character) {
            return character == ',' || character == '}' || character == ']';
        }
    }
}
