package io.github.libtmux.format;

import java.util.ArrayList;
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

    /** One row's values, addressed by the field name that asked for them. */
    public final class Row {

        private final List<String> values;

        private Row(List<String> values) {
            this.values = values;
        }

        /** The field's value as tmux printed it. */
        public String text(String field) {
            return values.get(indexOf(field));
        }

        /**
         * The field as a whole number.
         *
         * @throws TmuxFormatException if tmux did not print one
         */
        public int number(String field) {
            String value = text(field);
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new TmuxFormatException(field + " was not a number", e);
            }
        }

        /**
         * The field as a whole count.
         *
         * @throws TmuxFormatException if tmux did not print one
         */
        public long count(String field) {
            String value = text(field);
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new TmuxFormatException(field + " was not a count", e);
            }
        }

        /**
         * The field as one of tmux's flags, which it prints as 0 or 1.
         *
         * @throws TmuxFormatException if tmux printed anything else
         */
        public boolean flag(String field) {
            return switch (text(field)) {
                case "0" -> false;
                case "1" -> true;
                default -> throw new TmuxFormatException(field + " was neither 0 nor 1");
            };
        }

        private int indexOf(String field) {
            int index = fields.indexOf(field);
            if (index < 0) {
                throw new IllegalArgumentException(field + " is not a field of this format");
            }
            return index;
        }
    }

    /**
     * Reads a whole listing back into rows.
     *
     * <p>tmux ends a row with a newline and frames it no other way, so a listing's lines are not its
     * rows: a value carrying a newline arrives as several. A working directory may contain one, and
     * anything running in a pane may change into it, so this is caller-reachable rather than
     * theoretical. A row is closed by carrying every separator, not by the line ending.
     *
     * @param lines the listing as tmux printed it
     * @throws TmuxFormatException if the listing ends mid-row, or a row does not have exactly the
     *     expected number of fields
     */
    public List<Row> rows(List<String> lines) {
        List<Row> rows = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        int separators = 0;
        boolean open = false;
        for (String line : lines) {
            if (open) {
                pending.append('\n');
            }
            pending.append(line);
            open = true;
            separators += occurrences(line);
            if (separators >= fields.size() - 1) {
                rows.add(new Row(split(pending.toString())));
                pending.setLength(0);
                separators = 0;
                open = false;
            }
        }
        if (open) {
            throw new TmuxFormatException("a tmux listing ended before its last row did");
        }
        return List.copyOf(rows);
    }

    private static int occurrences(String line) {
        int count = 0;
        for (int at = line.indexOf(SEPARATOR); at >= 0; at = line.indexOf(SEPARATOR, at + SEPARATOR.length())) {
            count++;
        }
        return count;
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
