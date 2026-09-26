package io.github.libtmux.junit5;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a command group the way tmux's command parser reads it: words in single quotes, a quote
 * spelled {@code '\''}, a line break spelled {@code "\n"}, commands separated by {@code ;}.
 */
final class Groups {

    private Groups() {}

    static List<List<String>> parse(String text) {
        List<List<String>> commands = new ArrayList<>();
        List<String> command = new ArrayList<>();
        StringBuilder word = null;
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character == ' ') {
                if (word != null) {
                    command.add(word.toString());
                    word = null;
                }
                index++;
            } else if (character == ';' && word == null) {
                if (!command.isEmpty()) {
                    commands.add(command);
                }
                command = new ArrayList<>();
                index++;
            } else {
                if (word == null) {
                    word = new StringBuilder();
                }
                if (character == '\'') {
                    int end = text.indexOf('\'', index + 1);
                    word.append(text, index + 1, end);
                    index = end + 1;
                } else if (character == '"') {
                    int end = text.indexOf('"', index + 1);
                    word.append(
                            text.substring(index + 1, end).replace("\\n", "\n").replace("\\r", "\r"));
                    index = end + 1;
                } else if (character == '\\' && index + 1 < text.length()) {
                    word.append(text.charAt(index + 1));
                    index += 2;
                } else {
                    word.append(character);
                    index++;
                }
            }
        }
        if (word != null) {
            command.add(word.toString());
        }
        if (!command.isEmpty()) {
            commands.add(command);
        }
        return commands;
    }
}
