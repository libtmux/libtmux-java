package io.github.libtmux.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes a tmux command for each of the two parsers tmux reads commands with.
 *
 * <p>Every argv this takes is literal: what a caller meant, with nothing escaped for either parser.
 */
public final class CommandStrings {

    private CommandStrings() {}

    /**
     * Several commands as the flat argv tmux's own argv parser reads.
     *
     * <p>tmux ends a command at an argument whose last byte is {@code ;}, and keeps the semicolon
     * instead when a backslash precedes it, so an argument ending in one is escaped here and a bare
     * {@code ;} separates commands.
     */
    public static List<String> arguments(List<List<String>> commands) {
        List<String> argv = new ArrayList<>();
        for (List<String> command : commands) {
            if (!argv.isEmpty()) {
                argv.add(";");
            }
            for (String argument : command) {
                argv.add(escape(argument));
            }
        }
        return List.copyOf(argv);
    }

    /** One command as the string tmux's command parser reads, which is what {@code if-shell} takes. */
    public static String stringify(List<String> argv) {
        StringBuilder text = new StringBuilder();
        for (String argument : argv) {
            if (text.length() > 0) {
                text.append(' ');
            }
            appendArgument(text, argument);
        }
        return text.toString();
    }

    /**
     * Several commands as the one string tmux's parser reads as a group.
     *
     * <p>Each argument is quoted, so a semicolon inside one stays part of it and only the separators
     * between commands end a command.
     */
    public static String group(List<List<String>> commands) {
        StringBuilder text = new StringBuilder();
        for (List<String> argv : commands) {
            if (text.length() > 0) {
                text.append(" ; ");
            }
            text.append(stringify(argv));
        }
        return text.toString();
    }

    private static String escape(String argument) {
        return argument.endsWith(";") ? argument.substring(0, argument.length() - 1) + "\\;" : argument;
    }

    private static void appendArgument(StringBuilder text, String argument) {
        text.append('\'');
        for (int index = 0; index < argument.length(); index++) {
            switch (argument.charAt(index)) {
                case '\0' -> throw new IllegalArgumentException("a tmux argument cannot contain NUL");
                case '\n' ->
                    text.append('\'').append('"').append("\\n").append('"').append('\'');
                case '\r' ->
                    text.append('\'').append('"').append("\\r").append('"').append('\'');
                case '\'' -> text.append("'\\''");
                default -> text.append(argument.charAt(index));
            }
        }
        text.append('\'');
    }
}
