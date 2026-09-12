package io.github.libtmux.workspace.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.ParseResult;

final class LoadProgress {
    private static final Pattern TOKENS = Pattern.compile("\\{\\{|}}|\\{([^{}]+)}");
    private static final Map<String, String> PRESETS = Map.of(
            "default", "Loading workspace: {session} {bar} {progress} {window}",
            "minimal", "Loading workspace: {session} [{window_progress}]",
            "window", "Loading workspace: {session} {window_bar} {window_progress_rel}",
            "pane", "Loading workspace: {session} {pane_bar} {session_pane_progress}",
            "verbose",
                    "Loading workspace: {session} [window {window_index} of {window_total}, pane {pane_index} of {pane_total}] {window}");
    private final Main.Context context;
    private final String format;
    private final int columns;
    private final int panelRows;
    private final boolean color;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final Map<String, String> pending = new LinkedHashMap<>();
    private String session = "";
    private String workspace = "";
    private String window = "";
    private int windowTotal;
    private int windowIndex;
    private int windowsDone;
    private int paneTotal;
    private int paneIndex;
    private int panesDone;
    private int sessionPaneTotal;
    private int sessionPanesDone;
    private int painted;
    private long lastDraw;
    private boolean active;
    private boolean rawLineOpen;
    private boolean scriptDrawn;

    private LoadProgress(Main.Context context, String format, int lines, int rows, int columns, boolean color) {
        this.context = context;
        this.format = PRESETS.getOrDefault(format, format);
        this.columns = Math.max(1, columns - 1);
        panelRows = Math.min(lines == -1 ? rows : lines, Math.max(0, rows - 2));
        this.color = color;
    }

    static @Nullable LoadProgress create(Main.Context context, ParseResult args, String colorPolicy)
            throws IOException, InterruptedException {
        if (!context.processError()
                || Main.flag(args, "--no-progress")
                || context.environment().getOrDefault("TMUXP_PROGRESS", "1").equals("0")
                || context.environment().getOrDefault("TERM", "").equals("dumb")) return null;
        String value = args.hasMatchedOption("--progress-lines")
                ? args.matchedOptionValue("--progress-lines", 3).toString()
                : context.environment().getOrDefault("TMUXP_PROGRESS_LINES", "3");
        int lines;
        try {
            lines = Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw Main.usage("progress lines must be an integer at least -1");
        }
        if (lines < -1) throw Main.usage("progress lines must be an integer at least -1");
        var builder = new ProcessBuilder(
                        "/bin/sh", "-c", "test -t 2 || exit 1; /bin/stty size <&2 2>/dev/null || printf '24 80\\n'")
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().clear();
        builder.environment().putAll(context.environment());
        Process probe = builder.start();
        probe.getOutputStream().close();
        try (var output = probe.getInputStream()) {
            if (!probe.waitFor(1, TimeUnit.SECONDS) || probe.exitValue() != 0) return null;
            String size = new String(output.readAllBytes(), StandardCharsets.UTF_8).strip();
            var dimensions = Pattern.compile("([0-9]+) +([0-9]+)").matcher(size);
            int rows = dimensions.matches() ? Integer.parseInt(dimensions.group(1)) : 24;
            int columns = dimensions.matches() ? Integer.parseInt(dimensions.group(2)) : 80;
            String format = args.matchedOptionValue(
                    "--progress-format", context.environment().getOrDefault("TMUXP_PROGRESS_FORMAT", "default"));
            return new LoadProgress(
                    context,
                    format,
                    lines,
                    rows > 0 ? rows : 24,
                    columns > 0 ? columns : 80,
                    Reporter.colorEnabled(context.environment(), colorPolicy, true));
        } finally {
            if (probe.isAlive()) {
                probe.descendants().forEach(ProcessHandle::destroyForcibly);
                probe.destroyForcibly().waitFor();
            }
        }
    }

    void event(String name, JsonNode data) throws IOException {
        switch (name) {
            case "workspace-started" -> {
                active = true;
                session = data.path("session_name").asText();
                workspace = data.path("input").asText();
                window = "";
                windowTotal = data.path("window_total").asInt();
                sessionPaneTotal = data.path("session_pane_total").asInt();
                windowIndex = windowsDone = paneIndex = panesDone = sessionPanesDone = paneTotal = 0;
                history.clear();
                pending.clear();
                scriptDrawn = false;
                draw(true);
            }
            case "window-created" -> {
                window = data.path("window_name").asText();
                windowIndex++;
                paneTotal = data.path("pane_total").asInt();
                paneIndex = panesDone = 0;
                draw(false);
            }
            case "pane-created" -> {
                paneIndex++;
                draw(false);
            }
            case "pane-completed" -> {
                panesDone++;
                sessionPanesDone++;
                draw(false);
            }
            case "window-completed" -> {
                windowsDone++;
                draw(false);
            }
            case "script-output" -> {
                clear();
                String text = data.path("text").asText();
                if (panelRows == 0) return;
                String stream = data.path("stream").asText();
                String combined = pending.getOrDefault(stream, "") + text;
                combined = combined.substring(Math.max(0, combined.length() - 65_536));
                String[] lines = combined.split("\\r\\n|[\\r\\n]", -1);
                pending.remove(stream);
                if (!lines[lines.length - 1].isEmpty()) pending.put(stream, lines[lines.length - 1]);
                for (int index = 0; index < lines.length - 1; index++) {
                    history.addLast(lines[index]);
                    while (history.size() > panelRows) history.removeFirst();
                }
                int retained = history.stream().mapToInt(String::length).sum()
                        + pending.values().stream().mapToInt(String::length).sum();
                while (retained > 65_536 && !history.isEmpty())
                    retained -= history.removeFirst().length();
                var oldest = pending.values().iterator();
                while (retained > 65_536 && oldest.hasNext()) {
                    retained -= oldest.next().length();
                    oldest.remove();
                }
            }
            case "workspace-completed", "completed", "failed" -> {
                active = false;
                clear();
            }
            default -> {}
        }
    }

    void scriptWritten(String text) throws IOException {
        rawLineOpen = !text.isEmpty() && !text.endsWith("\n");
        if (panelRows != 0) {
            draw(!scriptDrawn);
            scriptDrawn = true;
        }
    }

    void clear() throws IOException {
        if (rawLineOpen) write("\n");
        rawLineOpen = false;
        if (painted != 0) write("\r" + (painted > 1 ? "\u001b[" + (painted - 1) + "A" : "") + "\u001b[0J");
        painted = 0;
    }

    private void draw(boolean force) throws IOException {
        long now = System.nanoTime();
        if (!active || (!force && now - lastDraw < TimeUnit.MILLISECONDS.toNanos(50))) return;
        var values = values();
        var matcher = TOKENS.matcher(Reporter.safe(format));
        StringBuilder label = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String value =
                    token.equals("{{") ? "{" : token.equals("}}") ? "}" : values.getOrDefault(matcher.group(1), token);
            matcher.appendReplacement(label, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(label);
        var rows = new ArrayList<String>();
        rows.add(label.toString());
        var panel = new ArrayList<>(history);
        panel.addAll(pending.values());
        for (int index = Math.max(0, panel.size() - panelRows); index < panel.size(); index++)
            rows.add(Reporter.style("info", panel.get(index), color));
        clear();
        painted = rows.size();
        lastDraw = now;
        write(String.join("\n", rows.stream().map(this::clip).toList()));
    }

    private Map<String, String> values() {
        var values = new HashMap<String, String>();
        values.put("session", Reporter.style("subject", session, color));
        values.put("workspace_path", Reporter.style("info", workspace, color));
        values.put("window", Reporter.style("heading", window, color));
        values.put("window_index", Integer.toString(windowIndex));
        values.put("window_total", Integer.toString(windowTotal));
        values.put("window_progress", ratio(windowIndex, windowTotal));
        values.put("window_progress_rel", ratio(windowsDone, windowTotal));
        values.put("windows_done", Integer.toString(windowsDone));
        values.put("windows_remaining", Integer.toString(Math.max(0, windowTotal - windowsDone)));
        values.put("pane_index", Integer.toString(paneIndex));
        values.put("pane_total", Integer.toString(paneTotal));
        values.put("pane_progress", ratio(paneIndex, paneTotal));
        values.put("pane_progress_rel", ratio(panesDone, paneTotal));
        values.put("pane_done", Integer.toString(panesDone));
        values.put("pane_remaining", Integer.toString(Math.max(0, paneTotal - panesDone)));
        values.put("session_pane_total", Integer.toString(sessionPaneTotal));
        values.put("session_panes_done", Integer.toString(sessionPanesDone));
        values.put("session_panes_remaining", Integer.toString(Math.max(0, sessionPaneTotal - sessionPanesDone)));
        values.put("session_pane_progress", ratio(sessionPanesDone, sessionPaneTotal));
        values.put(
                "overall_percent",
                Integer.toString(sessionPaneTotal == 0 ? 0 : 100 * sessionPanesDone / sessionPaneTotal));
        values.put("summary", "[" + windowsDone + " win, " + sessionPanesDone + " panes]");
        values.put("progress", ratio(windowIndex, windowTotal) + " win, " + ratio(paneIndex, paneTotal) + " pane");
        values.put("bar", bar(sessionPanesDone, sessionPaneTotal));
        values.put("pane_bar", bar(sessionPanesDone, sessionPaneTotal));
        values.put("window_bar", bar(windowsDone, windowTotal));
        values.put("status_icon", "");
        return values;
    }

    private static String ratio(int done, int total) {
        return total == 0 ? "" : done + "/" + total;
    }

    private String bar(int done, int total) {
        int filled = total == 0 ? 0 : Math.min(10, 10 * done / total);
        return Reporter.style("success", "#".repeat(filled), color)
                + Reporter.style("info", "-".repeat(10 - filled), color);
    }

    private String clip(String value) {
        StringBuilder result = new StringBuilder();
        int cells = 0;
        for (int at = 0; at < value.length(); ) {
            if (value.charAt(at) == '\u001b') {
                int end = value.indexOf('m', at);
                if (end < 0) break;
                result.append(value, at, end + 1);
                at = end + 1;
                continue;
            }
            int code = value.codePointAt(at);
            int width = Character.getType(code) == Character.NON_SPACING_MARK ? 0 : code < 128 ? 1 : 2;
            if (cells + width > columns) break;
            cells += width;
            result.appendCodePoint(code);
            at += Character.charCount(code);
        }
        return result + (color ? "\u001b[0m" : "");
    }

    private void write(String text) throws IOException {
        context.error().write(text.getBytes(StandardCharsets.UTF_8));
        context.error().flush();
    }
}
