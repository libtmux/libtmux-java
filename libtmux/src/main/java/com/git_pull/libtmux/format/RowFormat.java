package com.git_pull.libtmux.format;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A listing template and the reader for the rows it produces.
 *
 * <p>tmux returns a listing as plain lines, so what separates one field from the next is whatever
 * string the client put between them — and tmux gives no character a special meaning there. Any
 * fixed choice is therefore something a user can put in a window name. A window named with the
 * separator turns an n-field template into n+1 fields and shifts everything after it, so a pane id
 * arrives where a name belongs, and nothing downstream can tell.
 *
 * <p>The separator is generated once per process instead. Colliding with it means naming a window
 * with the exact token this process happened to produce.
 */
public final class RowFormat {

    private static final String SEPARATOR = Tokens.perProcess();

    private static final Pattern SPLITTER = Pattern.compile(Pattern.quote(SEPARATOR));

    private final List<String> fields;
    private final String template;

    private RowFormat(List<String> fields) {
        this.fields = fields;
        this.template = String.join(
                SEPARATOR, fields.stream().map(field -> "#{" + field + "}").toList());
    }

    /**
     * A format over the given tmux format names, in the order rows will report them.
     *
     * @param fields tmux format names, without the surrounding {@code #{}}
     */
    public static RowFormat of(String... fields) {
        if (fields.length == 0) {
            throw new IllegalArgumentException("a row format has no fields");
        }
        return new RowFormat(List.of(fields));
    }

    /** The argument to pass to tmux's {@code -F}. */
    public String template() {
        return template;
    }

    /** The token this process separates fields with. */
    public String separator() {
        return SEPARATOR;
    }

    /** How many fields a row must have. */
    public int size() {
        return fields.size();
    }

    /**
     * Reads one row back into its fields.
     *
     * @throws TmuxFormatException if the row does not have exactly the expected number of fields,
     *     which is the only chance to notice that something shifted
     */
    public List<String> split(String row) {
        List<String> values = List.of(SPLITTER.split(row, -1));
        if (values.size() != fields.size()) {
            // Counts only: a row carries names and pane content, and this message reaches logs.
            throw new TmuxFormatException("expected " + fields.size() + " fields in a tmux row, got " + values.size());
        }
        return values;
    }
}
