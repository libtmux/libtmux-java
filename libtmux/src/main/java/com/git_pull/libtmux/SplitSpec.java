package com.git_pull.libtmux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * How a pane should be split.
 *
 * <p>A description rather than an action: it can be built once, named, and applied to as many panes
 * as it suits. Nothing here contacts tmux, so a spec can be assembled on a machine that has none,
 * and the version rules it depends on are checked when it is applied rather than when it is written
 * — the same spec is legal against one server and not another.
 *
 * <p>A builder-built class rather than a record for the reason {@link ServerConfig} gives: a
 * record's canonical constructor is public API, and tmux keeps adding flags to {@code split-window}
 * — six of them in 3.7 alone.
 *
 * <pre>{@code
 * Pane side = pane.split(s -> s.toRight().percent(30));
 *
 * SplitSpec sidebar = SplitSpec.builder().toRight().percent(25).build();
 * left.split(sidebar);
 * right.split(sidebar);
 * }</pre>
 */
public final class SplitSpec {

    /** Every option below was refused by 3.6 and accepted by 3.7 on a real server. */
    private static final TmuxVersion PANE_EXTRAS_SINCE = new TmuxVersion(3, 7, "");

    private final SplitDirection direction;
    private final @Nullable PaneSize size;
    private final PaneStart start;
    private final @Nullable Path directory;
    private final Map<String, String> environment;
    private final boolean detached;
    private final boolean fullWindow;
    private final boolean zoomed;
    private final boolean keepOnExit;
    private final @Nullable String exitMessage;
    private final @Nullable String style;
    private final @Nullable String activeBorderStyle;
    private final @Nullable String inactiveBorderStyle;

    private SplitSpec(Builder builder) {
        this.direction = builder.direction;
        this.size = builder.size;
        this.start = builder.start;
        this.directory = builder.directory;
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        this.detached = builder.detached;
        this.fullWindow = builder.fullWindow;
        this.zoomed = builder.zoomed;
        this.keepOnExit = builder.keepOnExit;
        this.exitMessage = builder.exitMessage;
        this.style = builder.style;
        this.activeBorderStyle = builder.activeBorderStyle;
        this.inactiveBorderStyle = builder.inactiveBorderStyle;
    }

    /** A builder holding tmux's own defaults: below the target, half its size, running a shell. */
    public static Builder builder() {
        return new Builder();
    }

    /** Where the new pane goes. */
    public SplitDirection direction() {
        return direction;
    }

    /** How big the new pane should be, or empty to let tmux halve the target. */
    public Optional<PaneSize> size() {
        return Optional.ofNullable(size);
    }

    /** What runs in the new pane. */
    public PaneStart start() {
        return start;
    }

    /** Where the new pane starts, or empty to inherit. */
    public Optional<Path> directory() {
        return Optional.ofNullable(directory);
    }

    /** Variables set for the new pane, in the order they were given. */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Whether the new pane is left unfocused.
     *
     * <p>False by default, which is what {@code split-window} itself does. A session created through
     * {@link SessionSpec} is the opposite, because tmux cannot make one without attaching unless it
     * is told to detach.
     */
    public boolean detached() {
        return detached;
    }

    /** Whether the split spans the whole window rather than just the target pane. */
    public boolean fullWindow() {
        return fullWindow;
    }

    /** Whether the new pane is zoomed on arrival. */
    public boolean zoomed() {
        return zoomed;
    }

    /** Whether the pane stays after its command exits. Requires tmux 3.7. */
    public boolean keepOnExit() {
        return keepOnExit;
    }

    /** What a kept pane shows once its command has exited. Requires tmux 3.7. */
    public Optional<String> exitMessage() {
        return Optional.ofNullable(exitMessage);
    }

    /** The new pane's {@code window-style}. Requires tmux 3.7. */
    public Optional<String> style() {
        return Optional.ofNullable(style);
    }

    /** The new pane's {@code pane-active-border-style}. Requires tmux 3.7. */
    public Optional<String> activeBorderStyle() {
        return Optional.ofNullable(activeBorderStyle);
    }

    /** The new pane's {@code pane-border-style}. Requires tmux 3.7. */
    public Optional<String> inactiveBorderStyle() {
        return Optional.ofNullable(inactiveBorderStyle);
    }

    /**
     * The command that performs this split, reporting the new pane through {@code format}.
     *
     * @param target what to split, as a tmux target
     * @param format the row format the caller will read the result back with
     * @param running the version of the server about to run this
     * @throws UnsupportedTmuxVersion if the spec asks for something {@code running} does not have
     */
    List<String> argv(String target, String format, TmuxVersion running) {
        requireVersion(running);
        List<String> argv = new ArrayList<>(24);
        argv.add("split-window");
        argv.addAll(direction.flags());
        if (size != null) {
            argv.add("-l");
            argv.add(size.flagValue());
        }
        if (fullWindow) {
            argv.add("-f");
        }
        if (zoomed) {
            argv.add("-Z");
        }
        if (detached) {
            argv.add("-d");
        }
        if (directory != null) {
            argv.add("-c");
            argv.add(directory.toString());
        }
        for (Map.Entry<String, String> variable : environment.entrySet()) {
            argv.add("-e");
            argv.add(variable.getKey() + "=" + variable.getValue());
        }
        if (wantsEmptyPane()) {
            argv.add("-E");
        }
        if (style != null) {
            argv.add("-s");
            argv.add(style);
        }
        if (activeBorderStyle != null) {
            argv.add("-S");
            argv.add(activeBorderStyle);
        }
        if (inactiveBorderStyle != null) {
            argv.add("-R");
            argv.add(inactiveBorderStyle);
        }
        if (exitMessage != null) {
            argv.add("-m");
            argv.add(exitMessage);
        } else if (keepOnExit) {
            // -m already implies remain-on-exit; sending both says the same thing twice.
            argv.add("-k");
        }
        argv.add("-t");
        argv.add(target);
        argv.add("-P");
        argv.add("-F");
        argv.add(format);
        // Last, because everything after the command belongs to the command.
        argv.addAll(trailingCommand());
        return List.copyOf(argv);
    }

    /**
     * Whether tmux is being asked for a pane with no process.
     *
     * <p>A switch rather than an {@code instanceof} so that a fourth kind of start cannot be added
     * without the compiler pointing here.
     */
    private boolean wantsEmptyPane() {
        return switch (start) {
            case PaneStart.Shell shell -> false;
            case PaneStart.Command command -> false;
            case PaneStart.Empty empty -> true;
        };
    }

    /** What follows every flag, which only a command has. */
    private List<String> trailingCommand() {
        return switch (start) {
            case PaneStart.Shell shell -> List.of();
            case PaneStart.Command command -> command.argv();
            case PaneStart.Empty empty -> List.of();
        };
    }

    /**
     * Refuses before dispatch rather than letting tmux refuse.
     *
     * <p>Checked here and not in the builder because a spec is written without a server in sight,
     * and the same spec is legal against one server and not another.
     */
    private void requireVersion(TmuxVersion running) {
        if (running.atLeast(PANE_EXTRAS_SINCE)) {
            return;
        }
        String wanted = null;
        if (wantsEmptyPane()) {
            wanted = "an empty pane";
        } else if (exitMessage != null) {
            wanted = "an exit message";
        } else if (keepOnExit) {
            wanted = "keeping a pane after exit";
        } else if (style != null || activeBorderStyle != null || inactiveBorderStyle != null) {
            wanted = "pane styling on split";
        }
        if (wanted != null) {
            throw new UnsupportedTmuxVersion(wanted, PANE_EXTRAS_SINCE, running);
        }
    }

    @Override
    public String toString() {
        return "SplitSpec[" + direction + (size == null ? "" : " " + size.flagValue())
                + (start instanceof PaneStart.Shell ? "" : " " + start) + "]";
    }

    /** Collects the choices, each named for what it does rather than for the field it sets. */
    public static final class Builder {

        private SplitDirection direction = SplitDirection.BELOW;
        private @Nullable PaneSize size;
        private PaneStart start = PaneStart.shell();
        private @Nullable Path directory;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private boolean detached;
        private boolean fullWindow;
        private boolean zoomed;
        private boolean keepOnExit;
        private @Nullable String exitMessage;
        private @Nullable String style;
        private @Nullable String activeBorderStyle;
        private @Nullable String inactiveBorderStyle;

        private Builder() {}

        /** Puts the new pane below the target, which is what tmux does by default. */
        public Builder below() {
            direction = SplitDirection.BELOW;
            return this;
        }

        /** Puts the new pane above the target. */
        public Builder above() {
            direction = SplitDirection.ABOVE;
            return this;
        }

        /** Puts the new pane to the right of the target. */
        public Builder toRight() {
            direction = SplitDirection.RIGHT;
            return this;
        }

        /** Puts the new pane to the left of the target. */
        public Builder toLeft() {
            direction = SplitDirection.LEFT;
            return this;
        }

        /** Gives the new pane this many terminal cells. */
        public Builder cells(int count) {
            size = PaneSize.cells(count);
            return this;
        }

        /** Gives the new pane this share of what is being split. */
        public Builder percent(int share) {
            size = PaneSize.percent(share);
            return this;
        }

        /** Runs a command, which closes the pane when it exits unless {@link #keepOnExit} is set. */
        public Builder running(String... argv) {
            start = PaneStart.command(argv);
            return this;
        }

        /** Creates a pane with no process in it. Requires tmux 3.7. */
        public Builder empty() {
            start = PaneStart.empty();
            return this;
        }

        /** Sets what runs, for a caller holding a {@link PaneStart} already. */
        public Builder start(PaneStart start) {
            this.start = Objects.requireNonNull(start, "start");
            return this;
        }

        /** Starts the new pane in this directory. */
        public Builder in(Path directory) {
            this.directory = Objects.requireNonNull(directory, "directory");
            return this;
        }

        /** Sets one variable in the new pane's environment. */
        public Builder env(String name, String value) {
            environment.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Adds several variables to the new pane's environment, keeping any already set. */
        public Builder environment(Map<String, String> variables) {
            variables.forEach(this::env);
            return this;
        }

        /** Leaves the target pane focused, where tmux would move to the new one. */
        public Builder detached() {
            detached = true;
            return this;
        }

        /** Splits the whole window rather than just the target pane. */
        public Builder fullWindow() {
            fullWindow = true;
            return this;
        }

        /** Zooms the new pane on arrival. */
        public Builder zoomed() {
            zoomed = true;
            return this;
        }

        /** Keeps the pane after its command exits. Requires tmux 3.7. */
        public Builder keepOnExit() {
            keepOnExit = true;
            return this;
        }

        /** Keeps the pane after its command exits and shows this. Requires tmux 3.7. */
        public Builder keepOnExit(String message) {
            exitMessage = Objects.requireNonNull(message, "message");
            keepOnExit = true;
            return this;
        }

        /** Sets the new pane's {@code window-style}. Requires tmux 3.7. */
        public Builder style(String style) {
            this.style = Objects.requireNonNull(style, "style");
            return this;
        }

        /** Sets the new pane's {@code pane-active-border-style}. Requires tmux 3.7. */
        public Builder activeBorderStyle(String style) {
            this.activeBorderStyle = Objects.requireNonNull(style, "style");
            return this;
        }

        /** Sets the new pane's {@code pane-border-style}. Requires tmux 3.7. */
        public Builder inactiveBorderStyle(String style) {
            this.inactiveBorderStyle = Objects.requireNonNull(style, "style");
            return this;
        }

        /** The finished spec. */
        public SplitSpec build() {
            return new SplitSpec(this);
        }
    }
}
