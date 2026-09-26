package io.github.libtmux.workspace.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Zsh and Fish completion scripts, generated from the same {@link CommandSpec} tree {@code
 * --generate bash} reads. picocli only ships a Bash generator ({@link picocli.AutoComplete}), so
 * these walk the model by hand; both are kept to syntax-valid, not fully idiomatic, output.
 */
final class Completions {
    private Completions() {}

    static String zsh(String name, CommandSpec root) {
        StringBuilder script = new StringBuilder();
        script.append("#compdef ").append(name).append("\n\n");
        zshFunction(script, name, root);
        script.append('_').append(function(name)).append(" \"$@\"\n");
        return script.toString();
    }

    private static void zshFunction(StringBuilder script, String path, CommandSpec command) {
        Map<String, CommandLine> subcommands = command.subcommands();
        script.append('_').append(function(path)).append("() {\n");
        List<String> clauses = new ArrayList<>();
        for (OptionSpec option : command.options()) clauses.addAll(zshOptionClauses(option));
        if (!subcommands.isEmpty()) {
            clauses.add("'1: :(" + String.join(" ", subcommands.keySet()) + ")'");
            clauses.add("'*::arg:->args'");
        }
        script.append("  _arguments -C");
        for (String clause : clauses) script.append(" \\\n    ").append(clause);
        script.append('\n');
        if (!subcommands.isEmpty()) {
            script.append("  case $words[1] in\n");
            for (var entry : subcommands.entrySet())
                script.append("    ")
                        .append(entry.getKey())
                        .append(") _")
                        .append(function(path + " " + entry.getKey()))
                        .append(" ;;\n");
            script.append("  esac\n");
        }
        script.append("}\n\n");
        for (var entry : subcommands.entrySet())
            zshFunction(script, path + " " + entry.getKey(), entry.getValue().getCommandSpec());
    }

    private static List<String> zshOptionClauses(OptionSpec option) {
        String description = zshEscape(joinLines(option.description()));
        boolean takesArgument = option.arity().max() > 0;
        List<String> clauses = new ArrayList<>();
        for (String flag : option.names())
            clauses.add("'" + flag + (takesArgument ? "=" : "") + "[" + description + "]"
                    + (takesArgument ? ":value:" : "") + "'");
        return clauses;
    }

    static String fish(String name, CommandSpec root) {
        StringBuilder script = new StringBuilder();
        script.append("complete -c ").append(name).append(" -f\n");
        fishCommand(script, name, root, List.of());
        return script.toString();
    }

    private static void fishCommand(StringBuilder script, String name, CommandSpec command, List<String> path) {
        String seenParent = path.isEmpty() ? "" : "__fish_seen_subcommand_from " + path.getLast();
        String listCondition = path.isEmpty() ? "__fish_use_subcommand" : seenParent;
        for (var entry : command.subcommands().entrySet()) {
            CommandSpec child = entry.getValue().getCommandSpec();
            script.append("complete -c ")
                    .append(name)
                    .append(" -n '")
                    .append(fishEscape(listCondition))
                    .append("' -a ")
                    .append(entry.getKey())
                    .append(" -d '")
                    .append(fishEscape(joinLines(child.usageMessage().description())))
                    .append("'\n");
        }
        for (OptionSpec option : command.options()) {
            String description = fishEscape(joinLines(option.description()));
            boolean takesArgument = option.arity().max() > 0;
            for (String flag : option.names()) {
                script.append("complete -c ").append(name);
                if (!seenParent.isEmpty())
                    script.append(" -n '").append(fishEscape(seenParent)).append("'");
                script.append(flag.startsWith("--") ? " -l " + flag.substring(2) : " -s " + flag.substring(1));
                if (takesArgument) script.append(" -r");
                script.append(" -d '").append(description).append("'\n");
            }
        }
        for (var entry : command.subcommands().entrySet()) {
            var next = new ArrayList<>(path);
            next.add(entry.getKey());
            fishCommand(script, name, entry.getValue().getCommandSpec(), next);
        }
    }

    private static String function(String path) {
        return path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private static String joinLines(String[] lines) {
        return String.join(" ", lines);
    }

    private static String zshEscape(String text) {
        return text.replace("'", "'\\''");
    }

    private static String fishEscape(String text) {
        return text.replace("\\", "\\\\").replace("'", "\\'");
    }
}
