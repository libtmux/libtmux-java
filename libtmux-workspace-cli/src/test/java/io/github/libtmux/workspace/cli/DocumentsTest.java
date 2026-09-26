package io.github.libtmux.workspace.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link Documents#promptLine} is the one place a prompt reads stdin; {@link Execution}'s
 * multi-character answers and {@link Documents#confirm}'s yes/no both go through it so they cannot
 * disagree about what a typed line consumed.
 */
final class DocumentsTest {
    private static Main.Context context(String typed) {
        return new Main.Context(
                Map.of(),
                Path.of("").toAbsolutePath(),
                new ByteArrayInputStream(typed.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream());
    }

    @Test
    void promptLineReadsExactlyOneWholeLine() throws Exception {
        assertEquals("yes", Documents.promptLine(context("yes\n")));
    }

    @Test
    void promptLineAnswersNullAtEndOfInputWithNoLine() throws Exception {
        assertNull(Documents.promptLine(context("")));
    }
}
