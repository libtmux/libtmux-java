package io.github.libtmux.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Which platform encodings can carry a tmux argument.
 *
 * <p>The refusal itself is a property of the JVM's own locale, so it cannot be provoked from here;
 * {@code integration-tests}' locale lane runs a fork that has it. What is testable here is the
 * classification that lane depends on, including the names a real JDK reports.
 */
final class Utf8Test {

    @Test
    void utf8IsRecognisedUnderEveryNameAJdkReportsItBy() {
        assertTrue(Utf8.argumentsAreUtf8("UTF-8"));
        assertTrue(Utf8.argumentsAreUtf8("utf-8"), "charset names are not case-sensitive");
        assertTrue(Utf8.argumentsAreUtf8("UTF8"), "the alias a JDK reports on some platforms");
    }

    @Test
    void anythingThatCannotCarryAllOfUnicodeIsNot() {
        assertFalse(Utf8.argumentsAreUtf8("ANSI_X3.4-1968"), "what a JDK reports under LANG=C");
        assertFalse(Utf8.argumentsAreUtf8("US-ASCII"));
        // Encodes é and would look like it worked, then hands tmux a byte that is not UTF-8 at all.
        assertFalse(Utf8.argumentsAreUtf8("ISO-8859-1"));
    }

    @Test
    void anUnreadableEncodingNameIsTreatedAsUnableToCarryText() {
        assertFalse(Utf8.argumentsAreUtf8(""), "an absent property must not read as permission");
        assertFalse(Utf8.argumentsAreUtf8("not a charset"), "an illegal name throws, and refusing is the safe reading");
    }
}
