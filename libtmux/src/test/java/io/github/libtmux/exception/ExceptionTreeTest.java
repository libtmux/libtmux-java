package io.github.libtmux.exception;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.TmuxVersion;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.Idempotence;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One sealed tree, so every caller's decision about a failure is checked for completeness. */
final class ExceptionTreeTest {

    /** Compiles only while it covers every permitted subtype, which is the guarantee callers get. */
    private static String nextStep(LibTmuxException failure) {
        return switch (failure) {
            case TargetGoneException gone -> "re-resolve " + gone.getMessage();
            case ServerUnavailableException down -> "start a server " + down.getMessage();
            case CommandRejectedException rejected -> "change the request " + rejected.errorLines();
            case DispatchException.Failed failed -> failed.safeToRetry() ? "resend" : "re-read";
            case DispatchException.TimedOut late -> late.safeToRetry() ? "resend" : "re-read";
            case ControlEndedException ended -> "reattach " + ended.standardError();
            case UnsupportedFeatureException unsupported -> "skip " + unsupported.getMessage();
            case UnencodableTextException unencodable -> "fix the locale " + unencodable.getMessage();
            case MalformedResponseException malformed -> "report " + malformed.getMessage();
            case CardinalityException.NoMatch none -> "loosen " + none.getMessage();
            case CardinalityException.MultipleMatches many -> "tighten " + many.atLeast();
        };
    }

    @Test
    void everyFailureNamesWhatTheCallerDoesNext() {
        TmuxVersion old = TmuxVersion.parse("3.2a");
        assertAll(
                () -> assertEquals("re-resolve gone", nextStep(new TargetGoneException("gone"))),
                () -> assertEquals("start a server down", nextStep(new ServerUnavailableException("down"))),
                () -> assertEquals(
                        "change the request [duplicate session: a]",
                        nextStep(new CommandRejectedException(
                                "refused", "new-session", 1, List.of("duplicate session: a")))),
                () -> assertEquals(
                        "resend", nextStep(new DispatchException.Failed("x", DispatchOutcome.NOT_DISPATCHED, null))),
                () -> assertEquals(
                        "re-read", nextStep(new DispatchException.TimedOut("x", DispatchOutcome.UNKNOWN, null))),
                () -> assertEquals("reattach lost", nextStep(new ControlEndedException("lost", false, null))),
                () -> assertTrue(nextStep(new UnsupportedFeatureException("popups", old, old))
                        .startsWith("skip")),
                () -> assertEquals("fix the locale é", nextStep(new UnencodableTextException("é"))),
                () -> assertEquals("report bad row", nextStep(new MalformedResponseException("bad row"))),
                () -> assertEquals("loosen none", nextStep(new CardinalityException.NoMatch("none"))),
                () -> assertEquals("tighten 3", nextStep(new CardinalityException.MultipleMatches("many", 3))));
    }

    /**
     * An instantiable sealed class is treated as covered by its subclasses alone in a Scala match,
     * which then fails at run time on an instance of the class itself.
     */
    @Test
    void everyBranchIsAbstractAndEveryLeafIsFinal() {
        List<Class<?>> classes = new ArrayList<>();
        collect(LibTmuxException.class, classes);
        assertEquals(14, classes.size(), classes::toString);
        for (Class<?> type : classes) {
            if (type.isSealed()) {
                assertTrue(Modifier.isAbstract(type.getModifiers()), type + " is sealed but instantiable");
            } else {
                assertTrue(Modifier.isFinal(type.getModifiers()), type + " is a leaf that is not final");
            }
        }
    }

    @Test
    void useAfterCloseIsProgrammerErrorOutsideTheTree() {
        ServerClosedException closed = new ServerClosedException("server is closed", DispatchOutcome.NOT_DISPATCHED);

        assertFalse(LibTmuxException.class.isAssignableFrom(ServerClosedException.class));
        assertTrue(IllegalStateException.class.isAssignableFrom(ServerClosedException.class));
        assertEquals(DispatchOutcome.NOT_DISPATCHED, closed.outcome());
    }

    @Test
    void multipleMatchesAreAtLeastTwo() {
        assertThrows(IllegalArgumentException.class, () -> new CardinalityException.MultipleMatches("one", 1));
    }

    /** A request that never reached tmux, or one that only reads, is safe to send again; nothing else is. */
    @Test
    void resendingIsSafeOnlyWhenNothingRanOrNothingChanges() {
        assertAll(
                () -> assertTrue(DispatchOutcome.NOT_DISPATCHED.canRetryVerbatim(Idempotence.NOT_IDEMPOTENT)),
                () -> assertTrue(DispatchOutcome.NOT_DISPATCHED.canRetryVerbatim(Idempotence.IDEMPOTENT)),
                () -> assertTrue(DispatchOutcome.UNKNOWN.canRetryVerbatim(Idempotence.IDEMPOTENT)),
                () -> assertFalse(DispatchOutcome.UNKNOWN.canRetryVerbatim(Idempotence.NOT_IDEMPOTENT)),
                () -> assertFalse(DispatchOutcome.COMPLETE.canRetryVerbatim(Idempotence.IDEMPOTENT)),
                () -> assertFalse(DispatchOutcome.COMPLETE.canRetryVerbatim(Idempotence.NOT_IDEMPOTENT)));
    }

    private static void collect(Class<?> type, List<Class<?>> into) {
        into.add(type);
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (permitted != null) {
            for (Class<?> subtype : permitted) {
                collect(subtype, into);
            }
        }
    }
}
