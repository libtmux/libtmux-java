package io.github.libtmux.workspace.cli;

import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.Model.ArgGroupSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

final class Arguments {
    private Arguments() {}

    static CommandLine create() {
        CommandSpec root = command("tmux-workspace", "Load, capture and manage tmux workspaces.");
        root.version("tmux-workspace " + Main.version());
        root.addOption(OptionSpec.builder("-V", "--version").versionHelp(true).build());
        root.addOption(flag("Write structured JSON; workspace file encoding is independent.", "--json").toBuilder()
                .scopeType(CommandLine.ScopeType.INHERIT)
                .build());
        root.addOption(
                flag("Write flushed newline-delimited JSON; takes precedence over --json.", "--ndjson").toBuilder()
                        .scopeType(CommandLine.ScopeType.INHERIT)
                        .build());
        root.addOption(choice(
                        "Color policy; machine output never contains styling.",
                        List.of("auto", "always", "never"),
                        "--color")
                .toBuilder()
                .defaultValue("auto")
                .scopeType(CommandLine.ScopeType.INHERIT)
                .build());
        root.addOption(
                choice("Diagnostic verbosity.", List.of("debug", "info", "warning", "error", "critical"), "--log-level")
                        .toBuilder()
                        .defaultValue("warning")
                        .scopeType(CommandLine.ScopeType.INHERIT)
                        .build());
        root.addOption(choice(
                "Generate reference metadata or Bash completion without tmux.",
                List.of("schema", "bash"),
                "--generate"));

        CommandSpec load = command("load", "Build workspaces in order; -s applies to the last input.");
        load.addPositional(positional("workspace-file", "0..*", "1..*", String[].class));
        sockets(load);
        load.addOption(value("tmux configuration file.", "-f"));
        load.addOption(value("Override the last workspace session name.", "-s"));
        load.addOption(flag("Answer yes to confirmation prompts.", "-y", "--yes"));
        load.addOption(flag("Load without attaching; required for machine output unless appending.", "-d"));
        load.addOption(flag("Append windows to the session containing TMUX_PANE.", "-a", "--append"));
        load.addArgGroup(ArgGroupSpec.builder()
                .exclusive(true)
                .multiplicity("0..1")
                .addArg(flag("Use 256 terminal colors.", "-2"))
                .addArg(flag("Legacy 88-color mode; rejected because tmux 3.2a and newer removed it.", "-8"))
                .build());
        load.addOption(value("Write operation diagnostics to this file.", "--log-file"));
        load.addOption(
                value("Progress preset or template; TMUXP_PROGRESS_FORMAT supplies the default.", "--progress-format"));
        load.addOption(OptionSpec.builder("--progress-lines")
                .type(Integer.class)
                .description("Script panel lines: 3 by default, 0 hides, -1 fits the terminal.")
                .build());
        load.addOption(flag("Disable progress; TMUXP_PROGRESS=0 also disables it.", "--no-progress"));
        root.addSubcommand("load", load);

        CommandSpec freeze =
                command("freeze", "Capture recoverable session topology; original command history is unavailable.");
        freeze.addPositional(positional("session", "0", "0..1", String.class));
        sockets(freeze);
        save(freeze, true);
        freeze.addOption(flag("Suppress status text, preserving prompts.", "-q", "--quiet"));
        root.addSubcommand("freeze", freeze);

        CommandSpec convert = command("convert", "Convert the complete workspace document between YAML and JSON.");
        convert.addPositional(positional("workspace-file", "0", "1", String.class));
        save(convert, false);
        root.addSubcommand("convert", convert);
        CommandSpec imports = command("import", "Import a Teamocil or Tmuxinator document.");
        for (String name : List.of("teamocil", "tmuxinator")) {
            CommandSpec importer = command(name, "Import " + name + " YAML without executing source code.");
            importer.addPositional(positional("workspace-file", "0", "1", String.class));
            save(importer, false);
            imports.addSubcommand(name, importer);
        }
        root.addSubcommand("import", imports);

        CommandSpec list = command("ls", "List local project and active global workspaces.");
        list.addOption(flag("Group workspaces by directory.", "--tree"));
        list.addOption(flag("Include the complete source configuration.", "--full"));
        root.addSubcommand("ls", list);
        CommandSpec search = command("search", "Search workspace names, paths, sessions, windows and pane commands.");
        search.addPositional(positional("query", "0..*", "0..*", String[].class));
        search.addOption(OptionSpec.builder("-f", "--field")
                .type(String[].class)
                .arity("1")
                .description("Restrict fields: name, session/s, path/p, window/w, pane; repeatable.")
                .build());
        search.addOption(flag("Ignore case.", "-i", "--ignore-case"));
        search.addOption(flag("Ignore case unless the pattern contains uppercase.", "-S", "--smart-case"));
        search.addOption(flag("Match literal strings.", "-F", "--fixed-strings"));
        search.addOption(flag("Match whole words.", "-w", "--word-regexp"));
        search.addOption(flag("Select workspaces that do not match.", "-v", "--invert-match"));
        search.addOption(flag("Match any query; the default requires every query.", "--any"));
        root.addSubcommand("search", search);

        CommandSpec edit = command("edit", "Resolve a workspace and wait for EDITOR; quoted arguments are supported.");
        edit.addPositional(positional("workspace-file", "0", "1", String.class));
        root.addSubcommand("edit", edit);
        root.addSubcommand(
                "debug-info",
                command("debug-info", "Report runtime and configuration diagnostics with home paths masked."));
        CommandSpec shell = command("shell", "Use a version-checked tmuxp 1.74.0 Python shell.");
        shell.addPositional(positional("session", "0", "0..1", String.class));
        shell.addPositional(positional("window", "1", "0..1", String.class));
        sockets(shell);
        shell.addOption(value("Evaluate Python code and return captured output.", "-c"));
        var backends = ArgGroupSpec.builder().exclusive(true).multiplicity("0..1");
        for (String backend : List.of("best", "pdb", "code", "ptipython", "ptpython", "ipython", "bpython")) {
            backends.addArg(flag("Select the " + backend + " Python backend.", "--" + backend));
        }
        shell.addArgGroup(backends.build());
        shell.addOption(flag("Load Python startup configuration.", "--use-pythonrc"));
        shell.addOption(flag("Disable Python startup configuration; last toggle wins.", "--no-startup"));
        shell.addOption(flag("Enable vi editing mode.", "--use-vi-mode"));
        shell.addOption(flag("Disable vi editing mode; last toggle wins.", "--no-vi-mode"));
        root.addSubcommand("shell", shell);
        return new CommandLine(root).setOverwrittenOptionsAllowed(true);
    }

    private static CommandSpec command(String name, String description) {
        CommandSpec command = CommandSpec.create().name(name);
        command.usageMessage().description(description);
        command.addOption(OptionSpec.builder("-h", "--help")
                .usageHelp(true)
                .description("Show help.")
                .build());
        return command;
    }

    private static PositionalParamSpec positional(String label, String index, String arity, Class<?> type) {
        return PositionalParamSpec.builder()
                .index(index)
                .arity(arity)
                .required(arity.startsWith("1"))
                .type(type)
                .paramLabel(label)
                .build();
    }

    private static OptionSpec flag(String help, String... names) {
        return OptionSpec.builder(names).type(boolean.class).description(help).build();
    }

    private static OptionSpec value(String help, String... names) {
        return OptionSpec.builder(names).type(String.class).description(help).build();
    }

    private static OptionSpec choice(String help, List<String> choices, String... names) {
        return OptionSpec.builder(names)
                .type(String.class)
                .completionCandidates(choices)
                .description(help)
                .converters(value -> {
                    if (!choices.contains(value))
                        throw new CommandLine.TypeConversionException("expected one of " + choices);
                    return value;
                })
                .build();
    }

    private static void sockets(CommandSpec command) {
        command.addOption(value("Explicit tmux socket path; takes precedence over -L.", "-S"));
        command.addOption(value("Named tmux socket.", "-L"));
    }

    private static void save(CommandSpec command, boolean aliases) {
        command.addOption(choice(
                "Workspace file encoding; machine stdout remains JSON. Default: YAML, or opposite source encoding for convert.",
                List.of("yaml", "json"),
                aliases ? new String[] {"-f", "--workspace-format"} : new String[] {"--workspace-format"}));
        command.addOption(value(
                "Save to this file; machine mode otherwise returns the document.",
                aliases ? new String[] {"-o", "--save-to"} : new String[] {"--save-to"}));
        command.addOption(flag("Replace an existing destination.", "--force"));
        command.addOption(flag("Answer yes to confirmation prompts.", "-y", "--yes"));
    }
}
