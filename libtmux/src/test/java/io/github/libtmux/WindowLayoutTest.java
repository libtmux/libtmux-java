package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

/** Which of tmux's two layout forms a reported string is, decided as tmux decides it. */
final class WindowLayoutTest {

    @Test
    void theChecksummedFormIsClassic() {
        WindowLayout layout = WindowLayout.of("c3a4,80x24,0,0,1");

        assertInstanceOf(WindowLayout.Classic.class, layout);
        assertEquals("c3a4,80x24,0,0,1", layout.value());
    }

    /** tmux dispatches on the first non-blank character opening an object, and nothing more. */
    @Test
    void anObjectIsJsonWhateverLeadsIt() {
        assertInstanceOf(WindowLayout.Json.class, WindowLayout.of("{\"width\":80}"));
        assertInstanceOf(WindowLayout.Json.class, WindowLayout.of("  {\"width\":80}"));
        assertEquals("  {\"width\":80}", WindowLayout.of("  {\"width\":80}").value(), "and kept exactly as reported");
    }
}
