package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.exception.CardinalityException;
import io.github.libtmux.exception.CommandRejectedException;
import io.github.libtmux.exception.ControlEndedException;
import io.github.libtmux.exception.DispatchException;
import io.github.libtmux.exception.MalformedResponseException;
import io.github.libtmux.exception.ServerUnavailableException;
import io.github.libtmux.exception.TargetGoneException;
import io.github.libtmux.exception.UnencodableTextException;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.Idempotence;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every branch of the sealed exception tree maps to one stable error code and its own retry
 * decision, so a model can branch on {@code error_code} instead of matching on {@code message}.
 */
final class AnswersTest {

    @Test
    void anAbsentServerIsNotRetryableAndKeepsTheSocketHint() {
        Answers.Classified classified =
                Answers.classify(new ServerUnavailableException("no server running"), "list_sessions");
        assertEquals("SERVER_UNAVAILABLE", classified.errorCode());
        assertFalse(classified.retryable());
        assertTrue(classified.message().contains("Check that the MCP process selected the socket you intended"));
    }

    @Test
    void aGoneTargetIsNotRetryableVerbatim() {
        Answers.Classified classified = Answers.classify(new TargetGoneException("no pane %9"), "get_pane_info");
        assertEquals("TARGET_GONE", classified.errorCode());
        assertFalse(classified.retryable());
    }

    @Test
    void aTmuxRefusalIsNotRetryableVerbatim() {
        Answers.Classified classified =
                Answers.classify(new CommandRejectedException("duplicate session"), "create_session");
        assertEquals("COMMAND_REJECTED", classified.errorCode());
        assertFalse(classified.retryable());
    }

    @Test
    void cardinalityFailuresNameWhichSideMissed() {
        assertEquals(
                "NO_MATCH",
                Answers.classify(new CardinalityException.NoMatch("nothing matched"), "x")
                        .errorCode());
        assertEquals(
                "MULTIPLE_MATCHES",
                Answers.classify(new CardinalityException.MultipleMatches("two matched", 2), "x")
                        .errorCode());
        assertFalse(Answers.classify(new CardinalityException.MultipleMatches("two matched", 2), "x")
                .retryable());
    }

    @Test
    void dispatchFailuresDeferRetryabilityToSafeToRetry() {
        DispatchException neverReached =
                new DispatchException.Failed("tmux never started", DispatchOutcome.NOT_DISPATCHED, null);
        assertTrue(neverReached.safeToRetry());
        Answers.Classified classifiedFailed = Answers.classify(neverReached, "run_shell_command");
        assertEquals("DISPATCH_FAILED", classifiedFailed.errorCode());
        assertTrue(classifiedFailed.retryable());

        DispatchException uncertainMutation = new DispatchException.TimedOut(
                "deadline passed", DispatchOutcome.UNKNOWN, Idempotence.NOT_IDEMPOTENT, null);
        assertFalse(uncertainMutation.safeToRetry());
        Answers.Classified classifiedTimedOut = Answers.classify(uncertainMutation, "run_shell_command");
        assertEquals("DISPATCH_TIMED_OUT", classifiedTimedOut.errorCode());
        assertFalse(classifiedTimedOut.retryable());
    }

    @Test
    void malformedUnencodableAndUnsupportedFailuresAreNotRetryable() {
        assertEquals(
                "MALFORMED_RESPONSE",
                Answers.classify(new MalformedResponseException("bad row"), "x").errorCode());
        assertEquals(
                "UNENCODABLE_TEXT",
                Answers.classify(new UnencodableTextException("cannot encode"), "x")
                        .errorCode());
        assertEquals(
                "UNSUPPORTED_FEATURE",
                Answers.classify(new UnsupportedFeatureException("needs a newer tmux"), "x")
                        .errorCode());
        assertEquals(
                "CONTROL_ENDED",
                Answers.classify(new ControlEndedException("", false, null), "x")
                        .errorCode());
    }

    @Test
    void aPreDispatchRefusalIsDistinctFromATmuxRejection() {
        Answers.Classified argument =
                Answers.classify(new IllegalArgumentException("'x' is not a direction"), "split_window");
        Answers.Classified state = Answers.classify(new IllegalStateException("Refused."), "kill_pane");
        assertEquals("REFUSED", argument.errorCode());
        assertEquals("REFUSED", state.errorCode());
        assertFalse(argument.retryable());
        assertFalse(state.retryable());
    }

    @Test
    void anUnanticipatedDefectNamesTheToolAndWarnsTheStateIsUnknown() {
        Answers.Classified classified =
                Answers.classify(new UnsupportedOperationException("a transport defect"), "list_sessions");
        assertEquals("INTERNAL_ERROR", classified.errorCode());
        assertFalse(classified.retryable());
        assertTrue(classified.message().contains("list_sessions"));
        assertTrue(classified.message().contains("a transport defect"));
    }

    @Test
    void failureCarriesTheClassificationAsMetaAndAsText() {
        McpSchema.CallToolResult result =
                Answers.failure(new TargetGoneException("no pane %9 on this server"), "get_pane_info");

        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertEquals(
                null,
                result.structuredContent(),
                "an error result must not carry the success outputSchema's"
                        + " structuredContent; a client that validates against it would reject this answer");
        Map<String, Object> meta = result.meta();
        assertEquals("TARGET_GONE", meta.get("error_code"));
        assertEquals(false, meta.get("retryable"));
        assertEquals("no pane %9 on this server", meta.get("message"));
        assertEquals(
                "no pane %9 on this server",
                ((McpSchema.TextContent) result.content().get(0)).text());
    }
}
