package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class NotificationBufferTest {

    @Test
    void repeatedUpdatesCoalesceAndDistinctUpdatesStayBounded() {
        NotificationBuffer notifications = new NotificationBuffer(2);

        assertTrue(notifications.offer("one"));
        assertTrue(notifications.offer("one"));
        assertTrue(notifications.offer("two"));
        assertFalse(notifications.offer("three"));
        assertEquals(2, notifications.size());
        assertEquals(Set.of("one", "two"), notifications.drain());
        assertEquals(0, notifications.size());
        assertTrue(notifications.offer("three"));
    }
}
