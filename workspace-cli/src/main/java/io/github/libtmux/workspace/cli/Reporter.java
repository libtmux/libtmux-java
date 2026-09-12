package io.github.libtmux.workspace.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

final class Reporter {
    private final Main.Context context;
    private final boolean json;
    private final boolean ndjson;
    private final boolean color;
    private final String command;
    private int sequence;

    Reporter(Main.Context context, ParseResult parsed) {
        this.context = context;
        boolean jsonMode = false;
        boolean ndjsonMode = false;
        ParseResult leaf = parsed;
        for (ParseResult node = parsed; node != null; node = node.subcommand()) {
            jsonMode |= Main.flag(node, "--json");
            ndjsonMode |= Main.flag(node, "--ndjson");
            leaf = node;
        }
        json = jsonMode;
        ndjson = ndjsonMode;
        command = leaf.commandSpec().name();
        String policy = parsed.commandSpec().findOption("--color").getValue();
        var env = context.environment();
        color = !machine()
                && env.getOrDefault("NO_COLOR", "").isEmpty()
                && !policy.equals("never")
                && (policy.equals("always")
                        || !env.getOrDefault("FORCE_COLOR", "").isEmpty()
                        || (!env.getOrDefault("CLICOLOR_FORCE", "0").equals("0")
                                && !env.getOrDefault("CLICOLOR_FORCE", "").isEmpty())
                        || (!env.getOrDefault("CLICOLOR", "1").equals("0") && Main.terminal()));
    }

    boolean machine() {
        return json || ndjson;
    }

    boolean streaming() {
        return ndjson;
    }

    synchronized void document(JsonNode value) throws IOException {
        context.output().write(Documents.JSON.writeValueAsBytes(value));
        context.output().write('\n');
        context.output().flush();
    }

    synchronized void event(String name, ObjectNode data) throws IOException {
        if (ndjson) {
            ObjectNode event = Documents.JSON
                    .createObjectNode()
                    .put("schema_version", 1)
                    .put("command", command)
                    .put("event", name)
                    .put("sequence", ++sequence);
            event.setAll(data);
            document(event);
        }
    }

    void records(ArrayNode records, boolean listing, ArrayNode directories, boolean tree) throws IOException {
        if (ndjson) {
            for (JsonNode record : records) document(record);
        } else if (json) {
            if (listing) {
                ObjectNode value = Documents.JSON.createObjectNode();
                value.set("workspaces", records);
                value.set("global_workspace_dirs", directories);
                document(value);
            } else document(records);
        } else {
            String previousDirectory = "";
            for (JsonNode record : records) {
                String path = record.path("path").asText();
                String name = record.path("name").asText();
                if (tree) {
                    int separator = path.lastIndexOf('/');
                    String directory = separator < 0 ? "." : path.substring(0, separator);
                    if (!directory.equals(previousDirectory)) line("heading", directory, "");
                    previousDirectory = directory;
                    line("subject", "  |-- " + name, path.substring(separator + 1));
                } else line("subject", name, path);
                if (record.has("config")) context.output().write(Documents.encode(record.path("config"), "yaml"));
            }
        }
    }

    void line(String role, String subject, String detail) throws IOException {
        String code =
                switch (role) {
                    case "heading" -> "1;96";
                    case "subject" -> "1;35";
                    case "success" -> "32";
                    case "warning" -> "33";
                    case "error" -> "31";
                    default -> "36";
                };
        String text = color
                ? "\u001b[" + code + "m" + safe(subject) + "\u001b[0m  \u001b[36m" + safe(detail) + "\u001b[0m\n"
                : safe(subject) + "  " + safe(detail) + "\n";
        context.output().write(text.getBytes(StandardCharsets.UTF_8));
    }

    static String safe(String text) {
        StringBuilder value = new StringBuilder();
        text.codePoints().forEach(code -> {
            if (Character.isISOControl(code)) value.append(String.format("\\u%04x", code));
            else value.appendCodePoint(code);
        });
        return value.toString();
    }

    static ObjectNode metadata(CommandSpec command) {
        ObjectNode value = Documents.JSON.createObjectNode().put("name", command.name());
        value.set(
                "description", Documents.JSON.valueToTree(command.usageMessage().description()));
        ArrayNode options = value.putArray("options");
        command.options().forEach(option -> {
            ObjectNode arg = options.addObject()
                    .put("type", option.type().getTypeName())
                    .put("arity", option.arity().toString())
                    .put("required", option.required());
            arg.set("names", Documents.JSON.valueToTree(Arrays.asList(option.names())));
            arg.put("default", option.defaultValue());
            arg.set("description", Documents.JSON.valueToTree(option.description()));
        });
        ArrayNode positionals = value.putArray("positionals");
        command.positionalParameters()
                .forEach(arg -> positionals
                        .addObject()
                        .put("name", arg.paramLabel())
                        .put("arity", arg.arity().toString())
                        .put("index", arg.index().toString())
                        .put("required", arg.required()));
        ArrayNode children = value.putArray("children");
        command.subcommands().values().forEach(child -> children.add(metadata(child.getCommandSpec())));
        return value;
    }
}
