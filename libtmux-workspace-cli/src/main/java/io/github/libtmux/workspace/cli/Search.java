package io.github.libtmux.workspace.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import picocli.CommandLine.ParseResult;

final class Search {
    private record Query(String field, Pattern expression) {}

    private final List<Query> queries = new ArrayList<>();
    private final List<String> fields;
    private final boolean any;
    private final boolean invert;

    Search(ParseResult args) {
        String[] terms = args.matchedPositionalValue(0, new String[0]);
        if (terms.length == 0) throw Main.usage("search requires at least one query");
        fields = java.util.Arrays.stream(args.matchedOptionValue("--field", new String[0]))
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(Search::field)
                .toList();
        for (String term : terms) {
            String restricted = "";
            int colon = term.indexOf(':');
            if (colon > 0
                    && Set.of("name", "session", "s", "path", "p", "window", "w", "pane")
                            .contains(term.substring(0, colon))) {
                restricted = field(term.substring(0, colon));
                term = term.substring(colon + 1);
            }
            boolean insensitive = Main.flag(args, "--ignore-case")
                    || (Main.flag(args, "--smart-case") && term.codePoints().noneMatch(Character::isUpperCase));
            String expression = Main.flag(args, "--fixed-strings") ? Pattern.quote(term) : term;
            if (Main.flag(args, "--word-regexp")) expression = "\\b(?:" + expression + ")\\b";
            try {
                queries.add(new Query(
                        restricted,
                        Pattern.compile(
                                expression,
                                Pattern.UNICODE_CHARACTER_CLASS
                                        | (insensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0))));
            } catch (PatternSyntaxException invalid) {
                throw Main.usage("invalid search pattern: " + invalid.getDescription());
            }
        }
        any = Main.flag(args, "--any");
        invert = Main.flag(args, "--invert-match");
    }

    private static String field(String name) {
        return switch (name) {
            case "name", "session", "path", "window", "pane" -> name;
            case "s" -> "session";
            case "p" -> "path";
            case "w" -> "window";
            default -> throw Main.usage("unknown search field: " + name);
        };
    }

    ArrayNode run(ArrayNode records) {
        ArrayNode output = Documents.JSON.createArrayNode();
        for (JsonNode record : records) {
            if (record.has("error")) continue;
            Map<String, List<String>> values = new LinkedHashMap<>();
            values.put("name", List.of(record.path("name").asText()));
            values.put("path", List.of(record.path("path").asText()));
            values.put("session", List.of(record.path("session_name").asText()));
            values.put("window", new ArrayList<>());
            values.put("pane", new ArrayList<>());
            for (JsonNode window : record.path("config").path("windows")) {
                values.get("window").add(window.path("window_name").asText());
                for (JsonNode pane : window.path("panes")) commands(pane, values.get("pane"));
            }
            ObjectNode matches = Documents.JSON.createObjectNode();
            int matched = 0;
            for (Query query : queries) {
                boolean found = false;
                for (var entry : values.entrySet()) {
                    if ((!query.field().isEmpty() && !query.field().equals(entry.getKey()))
                            || (!fields.isEmpty() && !fields.contains(entry.getKey()))) continue;
                    for (String value : entry.getValue()) {
                        if (query.expression().matcher(value).find()) {
                            found = true;
                            ArrayNode strings = matches.withArray(entry.getKey());
                            if (!java.util.stream.StreamSupport.stream(strings.spliterator(), false)
                                    .anyMatch(node -> node.asText().equals(value))) strings.add(value);
                        }
                    }
                }
                if (found) matched++;
            }
            if ((any ? matched > 0 : matched == queries.size()) != invert) {
                ObjectNode selected = output.addObject();
                for (String key : List.of("name", "path", "session_name", "source"))
                    selected.set(key, record.path(key));
                ArrayNode names = selected.putArray("matched_fields");
                matches.fieldNames().forEachRemaining(names::add);
                selected.set("matches", matches);
            }
        }
        return output;
    }

    private static void commands(JsonNode value, List<String> result) {
        if (value.isTextual()) result.add(value.asText());
        else if (value.isArray()) value.forEach(item -> commands(item, result));
        else if (value.isObject()) {
            if (value.has("shell_command")) commands(value.path("shell_command"), result);
            else if (value.has("cmd")) commands(value.path("cmd"), result);
        }
    }
}
