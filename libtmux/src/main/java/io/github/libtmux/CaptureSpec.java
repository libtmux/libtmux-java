package io.github.libtmux;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What part of a pane to read, and how.
 *
 * <p>The shape {@link SplitSpec} uses. A plain {@link Pane#capture()} reads what is on screen; this
 * is how to reach the scrollback above it, or to keep what tmux otherwise strips.
 *
 * <p>Lines are numbered from the top of the visible area, so {@code 0} is the first row on screen
 * and negatives climb into the history. {@code fromStartOfHistory()} is the whole of what tmux
 * kept.
 *
 * <pre>{@code
 * List<String> everything = pane.capture(c -> c.fromStartOfHistory());
 * List<String> lastTen = pane.capture(c -> c.from(-10));
 * }</pre>
 */
public final class CaptureSpec {

    /** {@code -T} arrived in 3.4, {@code -M} in 3.6, and {@code -H} and {@code -L} in 3.7. */
    private static final TmuxVersion TRIM_SINCE = new TmuxVersion(3, 4, "");

    private static final TmuxVersion MODE_SCREEN_SINCE = new TmuxVersion(3, 6, "");

    private static final TmuxVersion HYPERLINKS_SINCE = new TmuxVersion(3, 7, "");

    private final @Nullable String start;
    private final @Nullable String end;
    private final boolean escapeSequences;
    private final boolean escapeNonPrintable;
    private final boolean joinWrapped;
    private final boolean preserveTrailing;
    private final boolean trimTrailing;
    private final boolean alternateScreen;
    private final boolean pendingOnly;
    private final boolean modeScreen;
    private final boolean hyperlinks;
    private final boolean lineNumbers;

    private CaptureSpec(Builder builder) {
        this.start = builder.start;
        this.end = builder.end;
        this.escapeSequences = builder.escapeSequences;
        this.escapeNonPrintable = builder.escapeNonPrintable;
        this.joinWrapped = builder.joinWrapped;
        this.preserveTrailing = builder.preserveTrailing;
        this.trimTrailing = builder.trimTrailing;
        this.alternateScreen = builder.alternateScreen;
        this.pendingOnly = builder.pendingOnly;
        this.modeScreen = builder.modeScreen;
        this.hyperlinks = builder.hyperlinks;
        this.lineNumbers = builder.lineNumbers;
    }

    /** A builder that reads what is on screen and nothing else. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The command that reads the pane.
     *
     * @throws UnsupportedTmuxVersion if the spec asks for something {@code running} does not have
     */
    List<String> argv(String target, TmuxVersion running) {
        require(trimTrailing, TRIM_SINCE, running, "trimming trailing space");
        require(modeScreen, MODE_SCREEN_SINCE, running, "capturing the mode screen");
        require(hyperlinks, HYPERLINKS_SINCE, running, "capturing hyperlinks");
        require(lineNumbers, HYPERLINKS_SINCE, running, "capturing line numbers");

        List<String> argv = new ArrayList<>(20);
        argv.add("capture-pane");
        argv.add("-p");
        if (start != null) {
            argv.add("-S");
            argv.add(start);
        }
        if (end != null) {
            argv.add("-E");
            argv.add(end);
        }
        flag(argv, escapeSequences, "-e");
        flag(argv, escapeNonPrintable, "-C");
        flag(argv, joinWrapped, "-J");
        flag(argv, preserveTrailing, "-N");
        flag(argv, trimTrailing, "-T");
        flag(argv, alternateScreen, "-a");
        flag(argv, pendingOnly, "-P");
        flag(argv, modeScreen, "-M");
        flag(argv, hyperlinks, "-H");
        flag(argv, lineNumbers, "-L");
        argv.add("-t");
        argv.add(target);
        return List.copyOf(argv);
    }

    private static void flag(List<String> argv, boolean wanted, String flag) {
        if (wanted) {
            argv.add(flag);
        }
    }

    private static void require(boolean wanted, TmuxVersion since, TmuxVersion running, String feature) {
        if (wanted && !running.atLeast(since)) {
            throw new UnsupportedTmuxVersion(feature, since, running);
        }
    }

    /** Collects the choices, each named for what it does rather than for the flag it sets. */
    public static final class Builder {

        private @Nullable String start;
        private @Nullable String end;
        private boolean escapeSequences;
        private boolean escapeNonPrintable;
        private boolean joinWrapped;
        private boolean preserveTrailing;
        private boolean trimTrailing;
        private boolean alternateScreen;
        private boolean pendingOnly;
        private boolean modeScreen;
        private boolean hyperlinks;
        private boolean lineNumbers;

        private Builder() {}

        /**
         * Starts at a line, counted from the top of the visible area.
         *
         * @param line {@code 0} is the first row on screen; negatives climb into the scrollback
         */
        public Builder from(int line) {
            start = Integer.toString(line);
            return this;
        }

        /** Starts at the oldest line tmux still has. */
        public Builder fromStartOfHistory() {
            start = "-";
            return this;
        }

        /** Ends at a line, counted the same way as {@link #from}. */
        public Builder to(int line) {
            end = Integer.toString(line);
            return this;
        }

        /** Ends at the newest line, including anything below the visible area. */
        public Builder toEndOfHistory() {
            end = "-";
            return this;
        }

        /** Keeps the escape sequences that colour and position the text. */
        public Builder withEscapeSequences() {
            escapeSequences = true;
            return this;
        }

        /** Writes anything unprintable as an octal escape rather than dropping it. */
        public Builder escapingNonPrintable() {
            escapeNonPrintable = true;
            return this;
        }

        /** Joins a line tmux wrapped back into the one line it was. */
        public Builder joiningWrappedLines() {
            joinWrapped = true;
            return this;
        }

        /** Keeps the spaces at the end of a line, which tmux otherwise drops. */
        public Builder preservingTrailingSpace() {
            preserveTrailing = true;
            return this;
        }

        /** Drops trailing space and the empty lines below the content. Requires tmux 3.4. */
        public Builder trimmingTrailingSpace() {
            trimTrailing = true;
            return this;
        }

        /** Reads the alternate screen, which is what a full-screen program draws on. */
        public Builder fromAlternateScreen() {
            alternateScreen = true;
            return this;
        }

        /** Reads only output tmux has not drawn yet. */
        public Builder pendingOnly() {
            pendingOnly = true;
            return this;
        }

        /** Reads what a mode is showing rather than the pane beneath it. Requires tmux 3.6. */
        public Builder fromModeScreen() {
            modeScreen = true;
            return this;
        }

        /** Keeps hyperlink markers. Requires tmux 3.7. */
        public Builder withHyperlinks() {
            hyperlinks = true;
            return this;
        }

        /** Prefixes each line with its number. Requires tmux 3.7. */
        public Builder withLineNumbers() {
            lineNumbers = true;
            return this;
        }

        /** The finished spec. */
        public CaptureSpec build() {
            return new CaptureSpec(this);
        }
    }
}
