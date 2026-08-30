package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.PaneId;
import java.util.List;
import org.junit.jupiter.api.Test;

final class UrisTest {

    @Test
    void resourceIdentifiersEncodeOnePathSegment() {
        assertEquals("tmux://panes/%251", Resources.paneUri(new PaneId("%1")));
        assertEquals("tmux://sessions/a%2Fb%20%25%3F%23", Resources.sessionUri("a/b %?#"));
    }

    @Test
    void templateValuesDecodeOnePathSegment() {
        assertEquals(List.of("%1"), Uris.values(Resources.PANE_TEMPLATE, "tmux://panes/%251"));
        assertEquals(List.of("a/b é"), Uris.values(Resources.SESSION_TEMPLATE, "tmux://sessions/a%2Fb%20%C3%A9"));
    }

    @Test
    void aTemplateMustConsumeTheWholeUri() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Uris.values(Resources.PANE_CONTENT_TEMPLATE, "tmux://panes/%251/content/extra"));
        assertThrows(
                IllegalArgumentException.class, () -> Uris.values(Resources.PANE_TEMPLATE, "tmux://panes/%251/extra"));
        assertThrows(IllegalArgumentException.class, () -> Uris.values(Resources.PANE_TEMPLATE, "tmux://panes/"));
    }

    @Test
    void malformedEscapesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Uris.values(Resources.PANE_TEMPLATE, "tmux://panes/%1"));
    }

    @Test
    void equivalentButNoncanonicalUrisAreRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> Uris.values(Resources.SESSION_TEMPLATE, "tmux://sessions/f%6Fo"));
        assertThrows(
                IllegalArgumentException.class, () -> Uris.values(Resources.SESSION_TEMPLATE, "tmux://sessions/a%2fb"));
        assertThrows(
                IllegalArgumentException.class, () -> Uris.values(Resources.SESSION_TEMPLATE, "tmux://sessions/a?b"));
    }
}
