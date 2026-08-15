package io.github.libtmux;

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
 * How a window should be made.
 *
 * <p>The shape {@link SplitSpec} uses, for the same reasons: a description that contacts nothing, so
 * it can be named, reused, and applied wherever it fits.
 *
 * <pre>{@code
 * Window logs = session.newWindow(w -> w.named("logs").running("journalctl", "-f"));
 * }</pre>
 */
public final class WindowSpec {

    /** 3.2a accepts {@code -c} on new-window and then ignores it; 3.3a is the first that honours it. */
    private static final TmuxVersion START_DIRECTORY_SINCE = new TmuxVersion(3, 3, "a");

    private final @Nullable String name;
    private final @Nullable Path directory;
    private final Map<String, String> environment;
    private final boolean detached;
    private final @Nullable WindowPlacement placement;
    private final @Nullable Integer index;
    private final boolean replaceExisting;
    private final boolean reuseExisting;
    private final List<String> command;

    private WindowSpec(Builder builder) {
        this.name = builder.name;
        this.directory = builder.directory;
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        this.detached = builder.detached;
        this.placement = builder.placement;
        this.index = builder.index;
        this.replaceExisting = builder.replaceExisting;
        this.reuseExisting = builder.reuseExisting;
        this.command = List.copyOf(builder.command);
    }

    /** A builder holding tmux's own defaults: a detached window at the end, running a shell. */
    public static Builder builder() {
        return new Builder();
    }

    /** The name the window is created with, or empty to let tmux choose. */
    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    /** Where the window's first pane starts, or empty to inherit. */
    public Optional<Path> directory() {
        return Optional.ofNullable(directory);
    }

    /** Variables set for the new window, in the order they were given. */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Whether the window is left unselected.
     *
     * <p>False by default, which is what {@code new-window} itself does.
     */
    public boolean detached() {
        return detached;
    }

    /** Where the window is inserted, or empty for tmux's own choice of index. */
    public Optional<WindowPlacement> placement() {
        return Optional.ofNullable(placement);
    }

    /** The index the window is created at, or empty for the next one tmux has free. */
    public Optional<Integer> index() {
        return Optional.ofNullable(index);
    }

    /**
     * Whether a window already at the target index is destroyed to make room.
     *
     * <p>Only means anything alongside {@link Builder#atIndex}: tmux has nothing to replace when it
     * is choosing the index itself.
     */
    public boolean replaceExisting() {
        return replaceExisting;
    }

    /** Whether a window that already carries this name is selected instead of a second being made. */
    public boolean reuseExisting() {
        return reuseExisting;
    }

    /** What the window's first pane runs, empty for the session's shell. */
    public List<String> command() {
        return command;
    }

    /**
     * The command that makes this window, reporting it through {@code format}.
     *
     * @param target the session, or the index, to create in
     * @param format the row format the caller will read the result back with
     * @param running the version of the server about to run this
     * @throws UnsupportedTmuxVersion if the spec asks for something {@code running} does not have
     */
    List<String> argv(String target, String format, TmuxVersion running) {
        if (directory != null && !running.atLeast(START_DIRECTORY_SINCE)) {
            // 3.2a takes -c on new-window and drops it, unlike -c on split-window, which it honours.
            throw new UnsupportedTmuxVersion("a start directory for a new window", START_DIRECTORY_SINCE, running);
        }
        List<String> argv = new ArrayList<>(20);
        argv.add("new-window");
        if (detached) {
            argv.add("-d");
        }
        if (placement != null) {
            argv.add(placement.flag());
        }
        if (replaceExisting) {
            argv.add("-k");
        }
        if (reuseExisting) {
            argv.add("-S");
        }
        if (name != null) {
            argv.add("-n");
            argv.add(name);
        }
        if (directory != null) {
            argv.add("-c");
            argv.add(directory.toString());
        }
        for (Map.Entry<String, String> variable : environment.entrySet()) {
            argv.add("-e");
            argv.add(variable.getKey() + "=" + variable.getValue());
        }
        argv.add("-t");
        // An index makes the target a winlink rather than the session, which is what -k replaces
        // and what tmux otherwise chooses for itself.
        argv.add(index == null ? target : target + ":" + index);
        argv.add("-P");
        argv.add("-F");
        argv.add(format);
        // Last, because everything after the command belongs to the command.
        argv.addAll(command);
        return List.copyOf(argv);
    }

    @Override
    public String toString() {
        return "WindowSpec[" + (name == null ? "unnamed" : name) + (placement == null ? "" : " " + placement) + "]";
    }

    /** Collects the choices, each named for what it does rather than for the field it sets. */
    public static final class Builder {

        private @Nullable String name;
        private @Nullable Path directory;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private boolean detached;
        private @Nullable WindowPlacement placement;
        private @Nullable Integer index;
        private boolean replaceExisting;
        private boolean reuseExisting;
        private List<String> command = List.of();

        private Builder() {}

        /** Names the window, where tmux would otherwise name it after what runs in it. */
        public Builder named(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** Puts the window immediately after the current one. */
        public Builder after() {
            placement = WindowPlacement.AFTER;
            return this;
        }

        /** Puts the window immediately before the current one. */
        public Builder before() {
            placement = WindowPlacement.BEFORE;
            return this;
        }

        /** Runs a command instead of the session's shell. */
        public Builder running(String... argv) {
            List<String> given = List.of(argv);
            if (given.isEmpty()) {
                throw new IllegalArgumentException("command is empty");
            }
            command = given;
            return this;
        }

        /** Starts the window in this directory. Requires tmux 3.3a. */
        public Builder in(Path directory) {
            this.directory = Objects.requireNonNull(directory, "directory");
            return this;
        }

        /** Sets one variable in the new window's environment. */
        public Builder env(String name, String value) {
            environment.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Adds several variables to the new window's environment, keeping any already set. */
        public Builder environment(Map<String, String> variables) {
            variables.forEach(this::env);
            return this;
        }

        /** Leaves the current window selected, where tmux would move to the new one. */
        public Builder detached() {
            detached = true;
            return this;
        }

        /** Creates the window at a chosen index rather than the next one tmux has free. */
        public Builder atIndex(int index) {
            if (index < 0) {
                throw new IllegalArgumentException("index is negative: " + index);
            }
            this.index = index;
            return this;
        }

        /** Destroys whatever window already sits at the index chosen by {@link #atIndex}. */
        public Builder replaceExisting() {
            replaceExisting = true;
            return this;
        }

        /**
         * Selects a window that already carries this name rather than making a second.
         *
         * <p>tmux reports nothing when it reuses one, so the answer comes from a lookup. Every other
         * creating call in this library is told what it made; this is the exception.
         */
        public Builder reuseExisting() {
            reuseExisting = true;
            return this;
        }

        /** The finished spec. */
        public WindowSpec build() {
            return new WindowSpec(this);
        }
    }
}
