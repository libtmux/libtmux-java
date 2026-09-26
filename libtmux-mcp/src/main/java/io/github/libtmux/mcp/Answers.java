package io.github.libtmux.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.libtmux.exception.CardinalityException;
import io.github.libtmux.exception.CommandRejectedException;
import io.github.libtmux.exception.ControlEndedException;
import io.github.libtmux.exception.DispatchException;
import io.github.libtmux.exception.MalformedResponseException;
import io.github.libtmux.exception.ServerUnavailableException;
import io.github.libtmux.exception.TargetGoneException;
import io.github.libtmux.exception.UnencodableTextException;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns what a tool worked out into what the protocol carries.
 *
 * <p>Every answer goes out twice: as {@code structuredContent} for a client that parses, and as the
 * same JSON in text for one that does not. That duplication is the protocol's own recommendation,
 * and it is why every tool answers with a record rather than a list — {@code structuredContent} is
 * an object, and an object with named fields is also what stops a model counting positions in an
 * array to find out how many panes it got.
 */
final class Answers {

    static final ObjectMapper JSON = mapper();

    private Answers() {}

    /**
     * How every answer is written.
     *
     * <p>Snake case, because that is what the arguments use. A model that sends {@code pane_id} and
     * reads back {@code paneId} is being asked to hold two conventions for one call.
     *
     * <p>A null field is left out entirely. Most answers have nothing to add to their {@code note},
     * and {@code "note":null} on every one of them is context spent to say nothing.
     */
    static ObjectMapper mapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .build();
    }

    /** An answer a model can read and a client can parse. */
    static McpSchema.CallToolResult ok(Object value) {
        Map<String, Object> structured = asObject(value);
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(null, render(structured), null)))
                .structuredContent(structured)
                .isError(false)
                .build();
    }

    /**
     * A failure a model can act on, with a stable code and a retry decision alongside the message.
     *
     * <p>Every failure this server raises is either a {@code LibTmuxException} branch, one of the two
     * validation refusals the tool layer itself throws, or a defect neither anticipated. Collapsing
     * all of those into one message string is how a model loses the one thing it needs to decide what
     * to do next: whether resending the exact same call could possibly help. {@code error_code} names
     * the branch and {@code retryable} answers that question; {@code message} is the same prose a
     * caller not reading {@code _meta} still gets from {@code content[0].text}.
     *
     * <p>Carried in {@code _meta} rather than {@code structuredContent}: a tool's {@code
     * outputSchema} describes its success shape, and a client that validates {@code
     * structuredContent} against it on every answer would reject this one for not matching.
     * {@code _meta} carries no such schema.
     */
    static McpSchema.CallToolResult failure(RuntimeException cause, String toolName) {
        Classified classified = classify(cause, toolName);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("error_code", classified.errorCode());
        meta.put("retryable", classified.retryable());
        meta.put("message", classified.message());
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(null, classified.message(), null)))
                .meta(Collections.unmodifiableMap(meta))
                .isError(true)
                .build();
    }

    /** {@code errorCode} is stable across releases; branch on it rather than on {@code message}. */
    record Classified(String errorCode, boolean retryable, String message) {}

    /**
     * Names which branch of the sealed exception tree a failure took, and whether resending the exact
     * same call is safe.
     *
     * <p>{@link DispatchException#safeToRetry()} is the only case where that answer depends on
     * anything but the branch itself: dispatch failed or timed out with tmux's own state left
     * uncertain, and whether a second identical send can double an effect depends on what the request
     * changes. Every other branch means tmux either refused the request or never saw it because this
     * server's own guards did, so retrying verbatim repeats the same refusal.
     */
    static Classified classify(RuntimeException cause, String toolName) {
        return switch (cause) {
            case ServerUnavailableException e ->
                new Classified(
                        "SERVER_UNAVAILABLE",
                        false,
                        e.getMessage() + " Check that the MCP process selected the socket you intended.");
            case TargetGoneException e -> new Classified("TARGET_GONE", false, String.valueOf(e.getMessage()));
            case CommandRejectedException e ->
                new Classified("COMMAND_REJECTED", false, String.valueOf(e.getMessage()));
            case CardinalityException.NoMatch e -> new Classified("NO_MATCH", false, String.valueOf(e.getMessage()));
            case CardinalityException.MultipleMatches e ->
                new Classified("MULTIPLE_MATCHES", false, String.valueOf(e.getMessage()));
            case DispatchException e -> {
                String code =
                        switch (e) {
                            case DispatchException.Failed failed -> "DISPATCH_FAILED";
                            case DispatchException.TimedOut timedOut -> "DISPATCH_TIMED_OUT";
                        };
                yield new Classified(code, e.safeToRetry(), String.valueOf(e.getMessage()));
            }
            case MalformedResponseException e ->
                new Classified("MALFORMED_RESPONSE", false, String.valueOf(e.getMessage()));
            case UnencodableTextException e ->
                new Classified("UNENCODABLE_TEXT", false, String.valueOf(e.getMessage()));
            case UnsupportedFeatureException e ->
                new Classified("UNSUPPORTED_FEATURE", false, String.valueOf(e.getMessage()));
            case ControlEndedException e -> new Classified("CONTROL_ENDED", false, String.valueOf(e.getMessage()));
            case IllegalArgumentException e -> new Classified("REFUSED", false, String.valueOf(e.getMessage()));
            case IllegalStateException e -> new Classified("REFUSED", false, String.valueOf(e.getMessage()));
            default ->
                // A defect, not a refusal. Named so the model reads it as the tool's own error rather
                // than a JSON-RPC internal error a client may show nobody.
                new Classified(
                        "INTERNAL_ERROR",
                        false,
                        toolName + " failed unexpectedly: " + cause + ". What it changed in tmux, "
                                + "if anything, is unknown: read the target's state before retrying.");
        };
    }

    /** The complete nested MCP result, preserving structured data, content, metadata and error state. */
    static Map<String, Object> envelope(McpSchema.CallToolResult result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        if (result.meta() != null) {
            envelope.put("_meta", result.meta());
        }
        envelope.put("content", result.content());
        if (result.structuredContent() != null) {
            envelope.put("structuredContent", result.structuredContent());
        }
        envelope.put("isError", Boolean.TRUE.equals(result.isError()));
        return Map.copyOf(envelope);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> already) {
            return (Map<String, Object>) already;
        }
        return JSON.convertValue(value, Map.class);
    }

    private static String render(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "could not render a tool result as JSON; retry the call with different arguments, "
                            + "since this output could not be serialized",
                    e);
        }
    }
}
