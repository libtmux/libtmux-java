package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The guide's field table is the formats {@link SnapshotCapture} reads. */
class SnapshotFieldGuideTest {

    private static final Pattern NAME = Pattern.compile("`([^`]+)`");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @Test
    void theGuideListsTheFormatsASnapshotReads() throws Exception {
        Path root = repoRoot();
        List<String> guide = Files.readAllLines(root.resolve("docs/guide/snapshots-and-handles.md"));
        String capture = Files.readString(root.resolve("libtmux/src/main/java/io/github/libtmux/SnapshotCapture.java"));
        List<String> panes = new ArrayList<>(quotedBlock(capture, "PANE_FIELDS"));
        panes.add(assignedString(capture, "FLOATING"));

        assertEquals(row(guide, "Server"), ofCall(capture, "PROCESS"));
        assertEquals(row(guide, "Session"), ofCall(capture, "SESSIONS"));
        assertEquals(row(guide, "Window"), ofCall(capture, "WINDOWS"));
        assertEquals(row(guide, "Pane"), panes);
        assertEquals(row(guide, "Client"), ofCall(capture, "CLIENTS"));
    }

    private static List<String> row(List<String> guide, String object) {
        int section = guide.indexOf("## What a snapshot stores");
        int next = -1;
        for (int i = section + 1; i < guide.size(); i++) {
            if (guide.get(i).startsWith("## ")) {
                next = i;
                break;
            }
        }
        if (section < 0 || next < 0) {
            throw new AssertionError("snapshot field section not found");
        }
        List<String> table = guide.subList(section, next);
        for (String line : table) {
            if (!line.startsWith("| " + object + " |")) {
                continue;
            }
            List<String> names = new ArrayList<>();
            Matcher matcher = NAME.matcher(line);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            return names;
        }
        throw new AssertionError("no " + object + " row in the snapshot field table");
    }

    private static List<String> ofCall(String source, String variable) {
        int at = source.indexOf("RowFormat " + variable);
        int open = source.indexOf("RowFormat.of(", at);
        int close = source.indexOf(");", open);
        return quoted(source.substring(open, close));
    }

    private static List<String> quotedBlock(String source, String variable) {
        int at = source.indexOf(variable + " =");
        int open = source.indexOf("{", at);
        int close = source.indexOf("};", open);
        return quoted(source.substring(open, close));
    }

    private static String assignedString(String source, String variable) {
        int at = source.indexOf("String " + variable + " =");
        return quoted(source.substring(at, source.indexOf(";", at))).getFirst();
    }

    private static List<String> quoted(String region) {
        List<String> names = new ArrayList<>();
        Matcher matcher = QUOTED.matcher(region);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("docs/guide/snapshots-and-handles.md"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("repo root not found from " + Path.of("").toAbsolutePath());
    }
}
