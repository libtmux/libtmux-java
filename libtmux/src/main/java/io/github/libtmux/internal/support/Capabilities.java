package io.github.libtmux.internal.support;

import io.github.libtmux.TmuxVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What the supported daemons can do, and the release defects this library works around.
 *
 * <p>One table decides both behaviour and the published manifest: the gates in {@code TmuxFeatures},
 * {@code ControlAcquisition}, {@code Layouts} and the rest read their releases from the records
 * here, and {@link #manifest()} writes the same records out as the root {@code capabilities.json}
 * that the documentation builds its capability pages from. Capabilities are inferred from the
 * daemon's version; tmux does not advertise them. The IDs are the shared vocabulary of the libtmux
 * ports, and a quirk that no caller can use stays a {@link Workaround}.
 */
public final class Capabilities {

    private Capabilities() {}

    /** What a gate does on a release that lacks the behaviour. */
    public enum Handling {
        REFUSE,
        FALLBACK,
        REPAIR;

        String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A tmux command a claim concerns, and the single-letter flags of it that matter. */
    public record Command(String command, List<String> flags) {
        public Command {
            flags = List.copyOf(flags);
        }
    }

    /**
     * A feature that exists from one release on.
     *
     * @param since the first release that has it
     * @param except exact releases at or above {@code since} that do not
     * @param error the simple name of the exception a refusal raises, when {@code handling} is
     *     {@link Handling#REFUSE}
     * @param apis the public API names that need it, as the generated API reference spells them
     */
    public record Capability(
            String id,
            String name,
            String summary,
            TmuxVersion since,
            List<TmuxVersion> except,
            Handling handling,
            @Nullable String error,
            List<String> apis,
            List<Command> commands) {
        public Capability {
            except = List.copyOf(except);
            apis = List.copyOf(apis);
            commands = List.copyOf(commands);
            if (handling == Handling.REFUSE && error == null) {
                throw new IllegalArgumentException(id + " refuses, so it names its error");
            }
        }

        /** Whether the daemon has the behaviour. */
        public boolean availableOn(TmuxVersion running) {
            return running.atLeast(since) && except.stream().noneMatch(running::sameRelease);
        }
    }

    /**
     * A defect in exact releases that this library refuses or repairs. Not a capability.
     *
     * @param releases the affected releases the manifest lists
     * @param until the first release that no longer has the defect, for a defect that spans every
     *     build between the first listed release and it, development builds included; null when
     *     only the listed releases are affected
     */
    public record Workaround(
            String id,
            String name,
            String summary,
            List<TmuxVersion> releases,
            @Nullable TmuxVersion until,
            Handling handling,
            List<String> apis,
            List<Command> commands) {
        public Workaround {
            releases = List.copyOf(releases);
            apis = List.copyOf(apis);
            commands = List.copyOf(commands);
            if (releases.isEmpty() || handling == Handling.FALLBACK) {
                throw new IllegalArgumentException(id + " names its releases and refuses or repairs");
            }
        }

        /** Whether the daemon has the defect. */
        public boolean affects(TmuxVersion running) {
            if (until != null) {
                return running.atLeast(releases.getFirst()) && running.compareTo(until) < 0;
            }
            return releases.stream().anyMatch(running::sameRelease);
        }
    }

    private static final String REFUSAL = "UnsupportedFeatureException";

    public static final Capability CONTROL_MODE = new Capability(
            "control-mode",
            "Control mode",
            "Attach a library-managed control client and stream events.",
            new TmuxVersion(3, 7, ""),
            List.of(),
            Handling.REFUSE,
            REFUSAL,
            List.of("Server.control", "ControlClient.attach", "ControlClient.attachUnfenced"),
            List.of(new Command("attach-session", List.of())));

    public static final Capability BUFFER_DELETE_NAMED = new Capability(
            "buffer-delete-named",
            "Named buffer deletion",
            "Delete one paste buffer by name without risking another buffer.",
            new TmuxVersion(3, 4, ""),
            List.of(),
            Handling.REFUSE,
            REFUSAL,
            List.of("Buffers.delete", "Server.cmd", "Server.run", "Server.batch"),
            List.of(new Command("delete-buffer", List.of("b"))));

    public static final Capability BUFFER_RENAME = new Capability(
            "buffer-rename",
            "Named buffer rename",
            "Rename one paste buffer by name without risking another buffer.",
            new TmuxVersion(3, 4, ""),
            List.of(),
            Handling.REFUSE,
            REFUSAL,
            List.of("Server.cmd", "Server.run", "Server.batch"),
            List.of(new Command("set-buffer", List.of("b", "n"))));

    public static final Capability LAYOUT_WITHOUT_CHECKSUM = new Capability(
            "layout-without-checksum",
            "Layout errors without a checksum",
            "select-layout reports a layout string that lacks the leading checksum instead of ending the server.",
            new TmuxVersion(3, 4, ""),
            List.of(),
            Handling.REFUSE,
            REFUSAL,
            List.of("Server.cmd", "Server.run", "Server.batch"),
            List.of(new Command("select-layout", List.of())));

    public static final Capability LAYOUT_MIRRORED_MAIN = new Capability(
            "layout-mirrored-main",
            "Mirrored main layouts",
            "Apply main-horizontal-mirrored and main-vertical-mirrored with select-layout.",
            new TmuxVersion(3, 5, ""),
            List.of(),
            Handling.REFUSE,
            REFUSAL,
            List.of("Window.selectLayout", "Layout.requireSupported"),
            List.of(new Command("select-layout", List.of())));

    public static final Capability LAYOUT_JSON = new Capability(
            "layout-json",
            "JSON layouts",
            "Apply a layout in the JSON form that tmux reports.",
            TmuxVersion.parse("3.8-rc2"),
            List.of(),
            Handling.REFUSE,
            REFUSAL,
            List.of("Window.selectLayout", "Layouts.require"),
            List.of(new Command("select-layout", List.of())));

    public static final Capability CAPTURE_MODE_SCREEN = new Capability(
            "capture-mode-screen",
            "Capture the mode screen",
            "Capture what a pane's mode, such as copy mode, is showing with capture-pane -M.",
            new TmuxVersion(3, 6, ""),
            List.of(),
            Handling.REFUSE,
            REFUSAL,
            List.of("Pane.capture", "CaptureSpec.Builder.fromModeScreen"),
            List.of(new Command("capture-pane", List.of("M"))));

    public static final Capability CAPTURE_LINE_FLAGS = new Capability(
            "capture-line-flags",
            "Capture hyperlinks and line numbers",
            "Capture hyperlinks and line numbers with capture-pane -H and -L.",
            new TmuxVersion(3, 7, ""),
            List.of(),
            Handling.REFUSE,
            REFUSAL,
            List.of("Pane.capture", "CaptureSpec.Builder.withHyperlinks", "CaptureSpec.Builder.withLineNumbers"),
            List.of(new Command("capture-pane", List.of("H", "L"))));

    public static final Capability CLIPBOARD_QUERY = new Capability(
            "clipboard-query",
            "Clipboard query",
            "Ask a client's terminal for its clipboard with refresh-client -l. On 3.3a the server exits when a"
                    + " client attached from xterm or tmux-256color receives it, and on 3.4 to 3.6 it exits"
                    + " when the client leaves before its terminal answers.",
            new TmuxVersion(3, 6, "a"),
            List.of(),
            Handling.REFUSE,
            REFUSAL,
            List.of("Server.cmd", "Server.run", "Server.batch"),
            List.of(new Command("refresh-client", List.of("l"))));

    public static final Capability CAPTURE_TRIM_BLANK_CELLS = new Capability(
            "capture-trim-blank-cells",
            "Trim blank cells from a capture",
            "Stop a capture at the last used cell with capture-pane -T.",
            new TmuxVersion(3, 4, ""),
            List.of(),
            Handling.REFUSE,
            REFUSAL,
            List.of("Pane.capture", "CaptureSpec.Builder.trimmingTrailingSpace"),
            List.of(new Command("capture-pane", List.of("T"))));

    public static final Workaround RUN_SHELL_OUTPUT_IN_COPY_MODE = new Workaround(
            "run-shell-output-in-copy-mode",
            "run-shell output goes to the pane",
            "With no target pane, run-shell sends job output to a pane's view mode instead of the"
                    + " command's own output, so Shell.capturing refuses.",
            List.of(new TmuxVersion(3, 3, "a"), new TmuxVersion(3, 4, "")),
            new TmuxVersion(3, 5, ""),
            Handling.REFUSE,
            List.of("Shell.capturing"),
            List.of(new Command("run-shell", List.of())));

    public static final Workaround BREAK_PANE_MISNAMES_WINDOWS = new Workaround(
            "break-pane-misnames-windows",
            "break-pane misnames the window",
            "On 3.7, break-pane gives the new window the default name when -n is given, and a break with no"
                    + " name can end the server, so Pane.breakOut names the window afterwards and a literal or"
                    + " unnamed break is refused.",
            List.of(new TmuxVersion(3, 7, "")),
            null,
            Handling.REPAIR,
            List.of("Pane.breakOut", "Server.cmd", "Server.run", "Server.batch"),
            List.of(new Command("break-pane", List.of("n"))));

    public static final Workaround SPLIT_WINDOW_PERCENT_SIZE = new Workaround(
            "split-window-percent-size",
            "split-window -p reads -l",
            "On 3.4, split-window -p reads its value from -l, so a literal -p is refused and the typed"
                    + " split sends the percentage with -l.",
            List.of(new TmuxVersion(3, 4, "")),
            null,
            Handling.REFUSE,
            List.of("Pane.split", "Window.split", "Server.cmd", "Server.run", "Server.batch"),
            List.of(new Command("split-window", List.of("p"))));

    public static final Workaround NAME_SEPARATORS_REJECTED = new Workaround(
            "name-separators-rejected",
            "Names with a colon or a period",
            "On 3.7, new-session, new-window, rename-session and rename-window reject a name that"
                    + " contains a colon or a period, so the library refuses it first.",
            List.of(new TmuxVersion(3, 7, "")),
            null,
            Handling.REFUSE,
            List.of("Server.newSession", "Session.newWindow", "Session.rename", "Window.rename"),
            List.of(
                    new Command("new-session", List.of("s")),
                    new Command("new-window", List.of("n")),
                    new Command("rename-session", List.of()),
                    new Command("rename-window", List.of())));

    private static final List<Capability> CAPABILITIES = List.of(
            CONTROL_MODE,
            BUFFER_DELETE_NAMED,
            BUFFER_RENAME,
            LAYOUT_WITHOUT_CHECKSUM,
            LAYOUT_MIRRORED_MAIN,
            LAYOUT_JSON,
            CAPTURE_TRIM_BLANK_CELLS,
            CAPTURE_MODE_SCREEN,
            CAPTURE_LINE_FLAGS,
            CLIPBOARD_QUERY);

    private static final List<Workaround> WORKAROUNDS = List.of(
            RUN_SHELL_OUTPUT_IN_COPY_MODE,
            BREAK_PANE_MISNAMES_WINDOWS,
            SPLIT_WINDOW_PERCENT_SIZE,
            NAME_SEPARATORS_REJECTED);

    /** Every capability the library gates, in manifest order. */
    public static List<Capability> capabilities() {
        return CAPABILITIES;
    }

    /** Every release defect the library refuses or repairs, in manifest order. */
    public static List<Workaround> workarounds() {
        return WORKAROUNDS;
    }

    /**
     * The manifest the documentation reads, as the root {@code capabilities.json}: deterministic,
     * keys sorted, two-space indent, one trailing newline.
     */
    public static String manifest() {
        Map<String, Object> root = new TreeMap<>();
        root.put("schema", "libtmux-capabilities/1");
        root.put("port", "java");
        root.put("floor", new TreeMap<>(Map.of("minimum", TmuxVersion.MINIMUM.toString())));
        root.put("capabilities", CAPABILITIES.stream().map(Capabilities::entry).toList());
        root.put("workarounds", WORKAROUNDS.stream().map(Capabilities::entry).toList());
        StringBuilder out = new StringBuilder();
        write(out, root, 0);
        return out.append('\n').toString();
    }

    private static Map<String, Object> entry(Capability capability) {
        Map<String, Object> entry = new TreeMap<>();
        entry.put("id", capability.id());
        entry.put("name", capability.name());
        entry.put("summary", capability.summary());
        entry.put("since", capability.since().toString());
        if (!capability.except().isEmpty()) {
            entry.put(
                    "except",
                    capability.except().stream().map(TmuxVersion::toString).toList());
        }
        entry.put("handling", capability.handling().json());
        if (capability.error() != null) {
            entry.put("error", capability.error());
        }
        entry.put("apis", capability.apis());
        entry.put("commands", commands(capability.commands()));
        return entry;
    }

    private static Map<String, Object> entry(Workaround workaround) {
        Map<String, Object> entry = new TreeMap<>();
        entry.put("id", workaround.id());
        entry.put("name", workaround.name());
        entry.put("summary", workaround.summary());
        entry.put(
                "releases",
                workaround.releases().stream().map(TmuxVersion::toString).toList());
        entry.put("handling", workaround.handling().json());
        entry.put("apis", workaround.apis());
        entry.put("commands", commands(workaround.commands()));
        return entry;
    }

    private static List<Map<String, Object>> commands(List<Command> commands) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Command command : commands) {
            Map<String, Object> entry = new TreeMap<>();
            entry.put("command", command.command());
            if (!command.flags().isEmpty()) {
                entry.put(
                        "flags",
                        command.flags().stream().map(flag -> "-" + flag).toList());
            }
            result.add(entry);
        }
        return result;
    }

    private static void write(StringBuilder out, Object value, int depth) {
        String indent = "  ".repeat(depth + 1);
        String closing = "  ".repeat(depth);
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> member : map.entrySet()) {
                if (!first) out.append(",\n");
                first = false;
                out.append(indent);
                string(out, (String) member.getKey());
                out.append(": ");
                write(out, member.getValue(), depth + 1);
            }
            out.append('\n').append(closing).append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(",\n");
                first = false;
                out.append(indent);
                write(out, item, depth + 1);
            }
            out.append('\n').append(closing).append(']');
        } else {
            string(out, (String) value);
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
