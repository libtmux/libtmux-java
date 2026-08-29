package io.github.libtmux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What to look for when opening the window browser narrowed to a match.
 *
 * <p>The shape {@link CaptureSpec} uses. tmux looks in a window's name, its title and its visible
 * content unless told otherwise; naming any of them narrows it to those.
 *
 * <pre>{@code
 * pane.findWindow(f -> f.matching("build").inName());
 * pane.findWindow(f -> f.matching("^err").asRegex().ignoringCase());
 * }</pre>
 *
 * <p>Every flag below exists on each supported release, so nothing here is version-gated.
 */
public final class FindSpec {

    private final String match;
    private final boolean name;
    private final boolean title;
    private final boolean content;
    private final boolean ignoreCase;
    private final boolean regex;
    private final boolean zoom;

    private FindSpec(Builder builder) {
        this.match = builder.match;
        this.name = builder.name;
        this.title = builder.title;
        this.content = builder.content;
        this.ignoreCase = builder.ignoreCase;
        this.regex = builder.regex;
        this.zoom = builder.zoom;
    }

    /** A builder that looks everywhere tmux looks by default. */
    public static Builder builder() {
        return new Builder();
    }

    /** What is being looked for. */
    public String match() {
        return match;
    }

    List<String> argv(String target) {
        List<String> argv = new ArrayList<>(10);
        argv.add("find-window");
        // Naming every field is what tmux does when told nothing, so it is left unsaid.
        if (!(name && title && content)) {
            flag(argv, name, "-N");
            flag(argv, title, "-T");
            flag(argv, content, "-C");
        }
        flag(argv, ignoreCase, "-i");
        flag(argv, regex, "-r");

        flag(argv, zoom, "-Z");
        argv.add("-t");
        argv.add(target);
        argv.add(match);
        return argv;
    }

    private static void flag(List<String> argv, boolean wanted, String flag) {
        if (wanted) {
            argv.add(flag);
        }
    }

    /** Collects what to look for, and where. */
    public static final class Builder {

        private String match = "";
        private boolean name = true;
        private boolean title = true;
        private boolean content = true;
        private boolean ignoreCase;
        private boolean regex;
        private boolean zoom;
        private boolean narrowed;

        private Builder() {}

        /** The text to look for. Required. */
        public Builder matching(String match) {
            this.match = Objects.requireNonNull(match, "match");
            return this;
        }

        /** Looks in the window's name. Naming any field stops the others being searched. */
        public Builder inName() {
            narrow();
            name = true;
            return this;
        }

        /** Looks in the window's title. */
        public Builder inTitle() {
            narrow();
            title = true;
            return this;
        }

        /** Looks in what the window is showing. */
        public Builder inContent() {
            narrow();
            content = true;
            return this;
        }

        /** Matches without regard to case. */
        public Builder ignoringCase() {
            this.ignoreCase = true;
            return this;
        }

        /** Reads the match as an extended regular expression rather than as text. */
        public Builder asRegex() {
            this.regex = true;
            return this;
        }

        /** Zooms the pane the browser opens in. */
        public Builder zooming() {
            this.zoom = true;
            return this;
        }

        /** The first field named replaces tmux's default of all three; later ones add to it. */
        private void narrow() {
            if (!narrowed) {
                name = false;
                title = false;
                content = false;
                narrowed = true;
            }
        }

        /**
         * Builds the spec.
         *
         * @throws IllegalArgumentException if nothing was given to match
         */
        public FindSpec build() {
            if (match.isEmpty()) {
                throw new IllegalArgumentException("a find has nothing to match");
            }
            return new FindSpec(this);
        }
    }
}
