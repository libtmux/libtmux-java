package io.github.libtmux.mcp;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.node.NullNode;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies the read aggregate's exact bound after the JSON-RPC id is known. */
final class ReadBatchResponses {

    private static final int MAX_WIRE_BYTES = 1_000_000;
    private static final Set<String> OUTPUT_FIELDS =
            Set.of("results", "succeeded", "failed", "stoppedAt", "truncated", "truncatedBytes", "onError");
    private static final String TRUNCATED_ERROR = "nested tool error was truncated to fit the batch response";

    private ReadBatchResponses() {}

    static McpSchema.JSONRPCMessage limit(McpSchema.JSONRPCMessage message) {
        if (wireBytes(message) <= MAX_WIRE_BYTES || !(message instanceof McpSchema.JSONRPCResponse response)) {
            return message;
        }
        if (!(response.result() instanceof McpSchema.CallToolResult result)
                || !(result.structuredContent() instanceof Map<?, ?> untyped)
                || !untyped.keySet().equals(OUTPUT_FIELDS)) {
            return message;
        }

        Map<String, Object> output = stringMap(untyped);
        Object untypedRows = output.get("results");
        if (!(untypedRows instanceof List<?> listed)) {
            return message;
        }
        List<Map<String, Object>> rows = new ArrayList<>(listed.size());
        for (Object untypedRow : listed) {
            if (!(untypedRow instanceof Map<?, ?> row)) {
                return message;
            }
            rows.add(new LinkedHashMap<>(stringMap(row)));
        }
        output.put("results", rows);

        while (true) {
            McpSchema.JSONRPCResponse bounded = response(response, result, output);
            if (wireBytes(bounded) <= MAX_WIRE_BYTES) {
                return bounded;
            }
            int row = largestResult(rows);
            if (row >= 0) {
                Map<String, Object> shortened = rows.get(row);
                Object removed = shortened.put("result", NullNode.getInstance());
                shortened.put("resultTruncated", true);
                recordTruncation(output, encodedBytes(removed) - encodedBytes(NullNode.getInstance()));
                continue;
            }
            row = largestError(rows);
            if (row >= 0) {
                Map<String, Object> shortened = rows.get(row);
                Object removed = shortened.put("error", TRUNCATED_ERROR);
                shortened.put("resultTruncated", true);
                recordTruncation(output, encodedBytes(removed) - encodedBytes(TRUNCATED_ERROR));
                continue;
            }
            throw new IllegalStateException("read batch metadata exceeds its fixed response limit");
        }
    }

    private static McpSchema.JSONRPCResponse response(
            McpSchema.JSONRPCResponse response, McpSchema.CallToolResult original, Map<String, Object> output) {
        McpSchema.CallToolResult rendered = Answers.ok(output);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                rendered.content(), rendered.isError(), rendered.structuredContent(), original.meta());
        return new McpSchema.JSONRPCResponse(response.jsonrpc(), response.id(), result, response.error());
    }

    private static int largestResult(List<Map<String, Object>> rows) {
        int selected = -1;
        int selectedBytes = -1;
        for (int index = 0; index < rows.size(); index++) {
            Object result = rows.get(index).get("result");
            if (result == null || result instanceof NullNode) {
                continue;
            }
            int bytes = encodedBytes(result);
            if (bytes > selectedBytes) {
                selected = index;
                selectedBytes = bytes;
            }
        }
        return selected;
    }

    private static int largestError(List<Map<String, Object>> rows) {
        int selected = -1;
        int selectedBytes = encodedBytes(TRUNCATED_ERROR);
        for (int index = 0; index < rows.size(); index++) {
            Object error = rows.get(index).get("error");
            if (!(error instanceof String) || TRUNCATED_ERROR.equals(error)) {
                continue;
            }
            int bytes = encodedBytes(error);
            if (bytes > selectedBytes) {
                selected = index;
                selectedBytes = bytes;
            }
        }
        return selected;
    }

    private static void recordTruncation(Map<String, Object> output, int removedBytes) {
        int alreadyRemoved =
                ((Number) Objects.requireNonNull(output.get("truncatedBytes"), "truncatedBytes")).intValue();
        output.put("truncated", true);
        output.put("truncatedBytes", Math.addExact(alreadyRemoved, Math.max(0, removedBytes)));
    }

    private static int wireBytes(McpSchema.JSONRPCMessage message) {
        return Math.addExact(encodedBytes(message), 1);
    }

    private static int encodedBytes(Object value) {
        try {
            return Answers.JSON.writeValueAsBytes(value).length;
        } catch (JacksonException failure) {
            throw new IllegalStateException("could not measure a read batch response", failure);
        }
    }

    private static Map<String, Object> stringMap(Map<?, ?> untyped) {
        Map<String, Object> typed = new LinkedHashMap<>();
        untyped.forEach((key, value) -> typed.put(String.valueOf(key), value));
        return typed;
    }
}
