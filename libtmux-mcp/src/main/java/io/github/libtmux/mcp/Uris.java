package io.github.libtmux.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the values out of a templated resource URI.
 *
 * <p>Only the one shape MCP resource templates use here — literal text with {@code {name}} standing
 * for one path segment. A general RFC 6570 implementation would be more than any URI in this server
 * needs, and every extra form it accepted would be one nothing produces.
 */
final class Uris {

    private Uris() {}

    /**
     * The values a URI supplies for a template's variables, in the order the template names them.
     *
     * @throws IllegalArgumentException if the URI does not fit the template
     */
    static List<String> values(String template, String uri) {
        List<String> values = new ArrayList<>();
        int templateAt = 0;
        int uriAt = 0;
        while (templateAt < template.length()) {
            int open = template.indexOf('{', templateAt);
            if (open < 0) {
                break;
            }
            String literal = template.substring(templateAt, open);
            if (!uri.startsWith(literal, uriAt)) {
                throw mismatch(template, uri);
            }
            uriAt += literal.length();
            int close = template.indexOf('}', open);
            if (close < 0) {
                throw new IllegalStateException("a resource template is missing a closing brace: " + template);
            }
            // A variable stands for one segment, so it ends where the next literal begins — or at the
            // next slash when the template ends with it.
            String after = template.substring(close + 1);
            int end = after.isEmpty() ? uri.length() : uri.indexOf(after, uriAt);
            if (end < 0) {
                throw mismatch(template, uri);
            }
            // Taken literally, never percent-decoded: a pane id starts with '%', which is the escape
            // character itself, so decoding turns tmux://panes/%0 into a malformed-escape error.
            values.add(uri.substring(uriAt, end));
            uriAt = end;
            templateAt = close + 1;
        }
        return List.copyOf(values);
    }

    private static IllegalArgumentException mismatch(String template, String uri) {
        return new IllegalArgumentException("'" + uri + "' is not a " + template);
    }
}
