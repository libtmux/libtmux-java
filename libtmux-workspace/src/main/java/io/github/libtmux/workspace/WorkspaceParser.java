package io.github.libtmux.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.libtmux.Layouts;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Converts the supported YAML shape into immutable workspace values. */
final class WorkspaceParser {

    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final Set<String> ROOT_FIELDS = Set.of("session_name", "windows");
    private static final Set<String> WINDOW_FIELDS = Set.of("window_name", "layout", "panes");
    private static final Set<String> PANE_FIELDS = Set.of("shell_command");

    private WorkspaceParser() {}

    static Workspace parse(String yaml) {
        List<JsonNode> documents;
        try (MappingIterator<JsonNode> values = YAML.readerFor(JsonNode.class).readValues(yaml)) {
            documents = values.readAll();
        } catch (JsonProcessingException e) {
            String detail = e.getOriginalMessage();
            if (detail.toLowerCase(Locale.ROOT).contains("duplicate")) {
                detail = "duplicate key: " + detail;
            }
            throw new IllegalArgumentException("the workspace is not readable YAML: " + detail, e);
        } catch (IOException e) {
            throw new IllegalArgumentException("the workspace is not readable YAML", e);
        }
        if (documents.size() != 1) {
            throw new IllegalArgumentException("the workspace must contain exactly one YAML document");
        }

        JsonNode root = object(documents.getFirst(), "$");
        rejectUnknown(root, ROOT_FIELDS, "$");
        String sessionName = text(root.get("session_name"), "$.session_name");
        JsonNode windowNodes = array(root.get("windows"), "$.windows");
        List<WindowSpec> windows = new ArrayList<>();
        for (int index = 0; index < windowNodes.size(); index++) {
            windows.add(window(windowNodes.get(index), "$.windows[" + index + "]"));
        }
        return new Workspace(sessionName, windows);
    }

    private static WindowSpec window(JsonNode node, String path) {
        JsonNode window = object(node, path);
        rejectUnknown(window, WINDOW_FIELDS, path);
        String name =
                optionalText(window.get("window_name"), path + ".window_name").orElse("");
        Optional<String> layout =
                optionalText(window.get("layout"), path + ".layout").map(Layouts::require);

        JsonNode paneNodes = window.get("panes");
        List<PaneSpec> panes = new ArrayList<>();
        if (paneNodes == null) {
            panes.add(new PaneSpec(List.of()));
        } else {
            JsonNode paneArray = array(paneNodes, path + ".panes");
            for (int index = 0; index < paneArray.size(); index++) {
                panes.add(pane(paneArray.get(index), path + ".panes[" + index + "]"));
            }
            if (panes.isEmpty()) {
                panes.add(new PaneSpec(List.of()));
            }
        }
        return new WindowSpec(name, layout, panes);
    }

    private static PaneSpec pane(JsonNode node, String path) {
        if (node.isTextual()) {
            return new PaneSpec(List.of(node.textValue()));
        }
        if (node.isArray()) {
            return new PaneSpec(texts(node, path));
        }
        JsonNode pane = object(node, path);
        rejectUnknown(pane, PANE_FIELDS, path);
        JsonNode command = pane.get("shell_command");
        if (command == null) {
            throw malformed(path + ".shell_command", "is required");
        }
        if (command.isTextual()) {
            return new PaneSpec(List.of(command.textValue()));
        }
        return new PaneSpec(texts(array(command, path + ".shell_command"), path + ".shell_command"));
    }

    private static List<String> texts(JsonNode nodes, String path) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            values.add(text(nodes.get(index), path + "[" + index + "]"));
        }
        return values;
    }

    private static JsonNode object(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw malformed(path, "must be a mapping");
        }
        return node;
    }

    private static JsonNode array(JsonNode node, String path) {
        if (node == null || !node.isArray()) {
            throw malformed(path, "must be a list");
        }
        return node;
    }

    private static String text(JsonNode node, String path) {
        if (node == null || !node.isTextual()) {
            throw malformed(path, "must be text");
        }
        return node.textValue();
    }

    private static Optional<String> optionalText(JsonNode node, String path) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(text(node, path));
    }

    private static void rejectUnknown(JsonNode object, Set<String> allowed, String path) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw malformed(path + "." + field, "is not supported");
            }
        });
    }

    private static IllegalArgumentException malformed(String path, String problem) {
        return new IllegalArgumentException(path + " " + problem);
    }
}
