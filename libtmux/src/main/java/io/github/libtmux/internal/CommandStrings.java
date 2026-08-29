package io.github.libtmux.internal;

import java.util.List;

/** Lossless conversion from a tmux argv to the command string its parser accepts. */
public final class CommandStrings {

    private CommandStrings() {}

    public static String stringify(List<String> argv) {
        StringBuilder text = new StringBuilder();
        for (String argument : argv) {
            if (text.length() > 0) {
                text.append(' ');
            }
            String literal = argument.endsWith("\\;") ? argument.substring(0, argument.length() - 2) + ';' : argument;
            appendArgument(text, literal);
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
