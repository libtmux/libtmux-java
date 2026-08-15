package com.git_pull.libtmux.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Framing a listing so that a name a user chose cannot be read as a different field.
 *
 * <p>A fixed separator is breakable: tmux gives U+241E no special meaning, so a window renamed to
 * contain one turns a three-field template into four fields and shifts every field after it. The
 * separator is therefore generated once per process, and a row that does not split into exactly the
 * expected number of fields is rejected rather than parsed anyway.
 */
final class RowFormatTest {

    private static final RowFormat WINDOWS = RowFormat.of("session_id", "window_id", "window_name");

    @Test
    void theTemplateAsksForEveryFieldInOrder() {
        String template = WINDOWS.template();
        String separator = WINDOWS.separator();

        assertEquals(
                List.of("#{session_id}", "#{window_id}", "#{window_name}"),
                List.of(template.split(java.util.regex.Pattern.quote(separator), -1)));
    }

    @Test
    void aRowSplitsBackIntoItsFields() {
        String row = String.join(WINDOWS.separator(), "$0", "@1", "editor");

        assertEquals(List.of("$0", "@1", "editor"), WINDOWS.split(row));
    }

    @Test
    void anEmptyFieldIsAFieldNotAMissingOne() {
        String row = String.join(WINDOWS.separator(), "$0", "@1", "");

        assertEquals(List.of("$0", "@1", ""), WINDOWS.split(row));
    }

    /** The exact case that rejects a fixed separator. */
    @Test
    void aNameContainingTheOldFixedSeparatorIsJustAName() {
        String row = String.join(WINDOWS.separator(), "$0", "@1", "win␞name");

        assertEquals(List.of("$0", "@1", "win␞name"), WINDOWS.split(row));
    }

    /**
     * A short row is the dangerous one: without this it parses, and every field after the offending
     * name is read as its neighbour, so a pane id arrives where a name belongs.
     */
    @Test
    void aRowWithTheWrongFieldCountIsRejected() {
        String separator = WINDOWS.separator();

        assertThrows(TmuxFormatException.class, () -> WINDOWS.split(String.join(separator, "$0", "@1")));
        assertThrows(TmuxFormatException.class, () -> WINDOWS.split(String.join(separator, "$0", "@1", "a", "b")));
    }

    @Test
    void theSeparatorCannotBeConfusedWithAFormatSequenceOrARegex() {
        String separator = WINDOWS.separator();

        assertFalse(separator.contains("#"), "a # would make tmux read the separator as a format");
        assertFalse(separator.contains("}"), "a brace would terminate the field before it");
        assertTrue(separator.matches("[0-9a-f]+"), "the separator is spliced into a regex: " + separator);
        assertTrue(separator.length() >= 16, "a short token is a guessable one: " + separator);
    }

    @Test
    void everyFormatInThisProcessSharesOneSeparator() {
        assertEquals(
                WINDOWS.separator(),
                RowFormat.of("pane_id").separator(),
                "one token per process keeps templates comparable and the cost off the hot path");
    }

    @Test
    void aFormatNeedsAtLeastOneField() {
        assertThrows(IllegalArgumentException.class, RowFormat::of);
    }
}
