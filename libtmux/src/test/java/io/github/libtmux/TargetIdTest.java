package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * tmux identifies each kind of object with its own sigil, so the types do too.
 *
 * <p>Every one of these is a string at the protocol boundary, and they are trivially swappable at a
 * call site: {@code kill-session -t %3} is accepted syntax that addresses nothing. Keeping them
 * distinct means the compiler rejects the swap instead of tmux reporting a missing target at
 * runtime.
 */
final class TargetIdTest {

    @Test
    void eachIdKeepsItsOwnSigil() {
        assertEquals("$0", new SessionId("$0").value());
        assertEquals("@1", new WindowId("@1").value());
        assertEquals("%2", new PaneId("%2").value());
    }

    @Test
    void anIdWithTheWrongSigilIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SessionId("@1"));
        assertThrows(IllegalArgumentException.class, () -> new WindowId("%2"));
        assertThrows(IllegalArgumentException.class, () -> new PaneId("$0"));
    }

    @Test
    void anIdWithoutASigilIsRejectedBecauseTmuxWouldReadItAsAName() {
        assertThrows(IllegalArgumentException.class, () -> new SessionId("0"));
        assertThrows(IllegalArgumentException.class, () -> new WindowId("1"));
        assertThrows(IllegalArgumentException.class, () -> new PaneId("2"));
        assertThrows(IllegalArgumentException.class, () -> new SessionId(""));
    }

    @Test
    void aSigilAloneIdentifiesNothing() {
        assertThrows(IllegalArgumentException.class, () -> new SessionId("$"));
        assertThrows(IllegalArgumentException.class, () -> new WindowId("@"));
        assertThrows(IllegalArgumentException.class, () -> new PaneId("%"));
    }

    @Test
    void twoIdsOfDifferentKindsCannotCompareEqual() {
        assertNotEquals(new SessionId("$1").value(), new WindowId("@1").value());
        assertEquals(new PaneId("%2"), new PaneId("%2"));
    }

    @Test
    void anIdIsItsOwnTargetText() {
        assertEquals("%2", new PaneId("%2").toString(), "an id goes straight into a -t argument");
    }

    @Test
    void aWindowIndexIsAPositionNotAnIdentity() {
        assertEquals(0, new WindowIndex(0).value());
        assertEquals("3", new WindowIndex(3).toString());
        assertThrows(IllegalArgumentException.class, () -> new WindowIndex(-1));
    }
}
