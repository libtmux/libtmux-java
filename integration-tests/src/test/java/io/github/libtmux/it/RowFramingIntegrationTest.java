package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.Server;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.format.TmuxFormatException;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The framing decision, measured against real tmux rather than against a string in a unit test.
 *
 * <p>tmux accepts a window name containing any character, including whatever a client picked as its
 * field separator. This proves the chosen framing survives that, and that the framing it replaced
 * does not — a separator that has never been broken is only a guess.
 */
@ExtendWith(TmuxExtension.class)
final class RowFramingIntegrationTest {

    private static final String HOSTILE = "win␞name";

    @Test
    void aHostileWindowNameStillReadsBackAsOneField(Server server) {
        RowFormat format = RowFormat.of("session_id", "window_id", "window_name");
        server.cmd("rename-window", "-t", "libtmux:", HOSTILE);

        List<String> rows =
                server.cmd("list-windows", "-a", "-F", format.template()).stdout();

        assertEquals(1, rows.size(), "the fixture has one window");
        List<String> fields = format.split(rows.get(0));
        assertEquals(3, fields.size());
        assertEquals(HOSTILE, fields.get(2), "the name tmux stored is the name we read");
    }

    /** The framing this replaced, run against the same tmux, so the choice rests on a measurement. */
    @Test
    void theFixedSeparatorItReplacedIsBrokenByTheSameName(Server server) {
        String separator = "␞";
        String template = "#{session_id}" + separator + "#{window_id}" + separator + "#{window_name}";
        server.cmd("rename-window", "-t", "libtmux:", HOSTILE);

        String row = server.cmd("list-windows", "-a", "-F", template).stdout().get(0);

        assertEquals(4, row.split(separator, -1).length, "a fixed separator yields one field too many");
    }

    @Test
    void aRowThatShiftedIsRejectedRatherThanParsed(Server server) {
        RowFormat wider = RowFormat.of("session_id", "window_id", "window_name", "window_index");
        RowFormat narrower = RowFormat.of("session_id", "window_id");

        String row = server.cmd("list-windows", "-a", "-F", wider.template())
                .stdout()
                .get(0);

        assertThrows(TmuxFormatException.class, () -> narrower.split(row));
    }
}
