package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.libtmux.Server;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Whatever a caller writes, tmux keeps, over both ways a command reaches it.
 *
 * <p>A process command hands tmux an argument vector; a control client writes one line that tmux
 * parses back into words. Each seeded value is written to a user option and read back by name, as
 * a format, and from the listing, so a value that loses or gains a character on the way in or out
 * fails with the value named, including a {@code $}, which tmux 3.4 escapes in everything it prints,
 * and a trailing line break. Buffers go the same two ways, compared without trailing line breaks,
 * which {@code Buffers.show} documents it cannot keep.
 *
 * <p>Control characters are in the alphabet but a carriage return is not: every read decodes tmux's
 * output with universal newlines, as Python libtmux does, so a lone {@code \r} reads back as a line
 * break on every path.
 */
@ExtendWith(TmuxExtension.class)
final class QuotingPropertyIntegrationTest {

    private static final String ALPHABET = "\t\n\u0001\u001b\u007fabcXYZ019 !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~é中😀";

    @Test
    void aValueSurvivesTheArgumentVectorAndTheControlLine(Server server) {
        List<String> lost = new ArrayList<>();
        try (ControlClient client = server.control(server.sessions().get(0))) {
            for (String value : values(new Random(20260924L), 200)) {
                server.globalOptions().set("@vector", value);
                String viaVector = server.globalOptions().get("@vector").orElse("<unset>");
                if (!viaVector.equals(value)) {
                    lost.add("vector " + quoted(value) + " -> " + quoted(viaVector));
                }
                String viaFormat = server.expand("#{@vector}");
                if (!viaFormat.equals(value)) {
                    lost.add("format " + quoted(value) + " -> " + quoted(viaFormat));
                }
                String viaListing = server.globalOptions().all().getOrDefault("@vector", "<unset>");
                if (!viaListing.equals(value)) {
                    lost.add("listing " + quoted(value) + " -> " + quoted(viaListing));
                }

                client.send(List.of("set-option", "-g", "@line", value));
                String viaLine = server.globalOptions().get("@line").orElse("<unset>");
                if (!viaLine.equals(value)) {
                    lost.add("line " + quoted(value) + " -> " + quoted(viaLine));
                }

                server.buffers().set("vector", value);
                String viaBuffer = server.buffers().show("vector");
                String inBuffer = value.replaceAll("\n+$", "");
                if (!viaBuffer.equals(inBuffer)) {
                    lost.add("buffer " + quoted(value) + " -> " + quoted(viaBuffer));
                }
                client.send(List.of("set-buffer", "-b", "line", "--", value));
                String viaLineBuffer = server.buffers().show("line");
                if (!viaLineBuffer.equals(inBuffer)) {
                    lost.add("line buffer " + quoted(value) + " -> " + quoted(viaLineBuffer));
                }
            }
        }
        assertEquals(List.of(), lost);
    }

    private static List<String> values(Random random, int count) {
        List<String> values = new ArrayList<>();
        int[] points = ALPHABET.codePoints().toArray();
        for (int index = 0; index < count; index++) {
            StringBuilder value = new StringBuilder();
            int length = 1 + random.nextInt(10);
            for (int character = 0; character < length; character++) {
                value.appendCodePoint(points[random.nextInt(points.length)]);
            }
            values.add(value.toString());
        }
        return values;
    }

    private static String quoted(String value) {
        return "[" + value + "]";
    }
}
