package com.git_pull.libtmux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * How a session should be made.
 *
 * <p>The shape {@link SplitSpec} and {@link WindowSpec} use. tmux's {@code -A}, which attaches when
 * the name is taken, is deliberately absent: attaching needs a terminal, and it fails with
 * {@code open terminal failed} on every supported release when there is none. Asking
 * {@link Server#hasSession} first does the same job from a process that has no tty, which is where a
 * library usually runs.
 *
 * <pre>{@code
 * Session build = server.newSession(s -> s.named("build").sized(new Dimensions(120, 40)));
 * }</pre>
 */
public final class SessionSpec {

    /** 3.2a accepts {@code -x}/{@code -y} for a detached session and then ignores them. */
    private static final TmuxVersion SIZE_SINCE = new TmuxVersion(3, 3, "a");

    private final @Nullable String name;
    private final @Nullable Path directory;
    private final Map<String, String> environment;
    private final @Nullable String windowName;
    private final @Nullable Dimensions size;
    private final boolean withoutSize;
    private final boolean detachOthers;
    private final List<String> clientFlags;
    private final List<String> command;

    private SessionSpec(Builder builder) {
        this.name = builder.name;
        this.directory = builder.directory;
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        this.windowName = builder.windowName;
        this.size = builder.size;
        this.withoutSize = builder.withoutSize;
        this.detachOthers = builder.detachOthers;
        this.clientFlags = List.copyOf(builder.clientFlags);
        this.command = List.copyOf(builder.command);
    }

    /** A builder holding tmux's own defaults: a detached session tmux names itself. */
    public static Builder builder() {
        return new Builder();
    }

    /** The session name, or empty to let tmux number it. */
    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    /** Where the session's first pane starts, or empty to inherit. */
    public Optional<Path> directory() {
        return Optional.ofNullable(directory);
    }

    /** Variables set for the new session, in the order they were given. */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Always true, and not a choice.
     *
     * <p>{@code new-session} attaches unless told not to, and attaching needs a terminal — from a
     * process without one it fails with {@code open terminal failed}. A library is usually called
     * from something that has no tty, so the session is always created detached. {@link WindowSpec}
     * and {@link SplitSpec} default the other way, because their commands need no terminal.
     */
    public boolean detached() {
        return true;
    }

    /** The name of the session's first window, or empty for tmux's choice. */
    public Optional<String> windowName() {
        return Optional.ofNullable(windowName);
    }

    /** How big the session is, or empty for tmux's default. Requires tmux 3.3a. */
    public Optional<Dimensions> size() {
        return Optional.ofNullable(size);
    }

    /** Whether tmux is told not to give the session a size at all. */
    public boolean withoutSize() {
        return withoutSize;
    }

    /** Whether other clients on this session are detached. */
    public boolean detachOthers() {
        return detachOthers;
    }

    /** Client flags, which tmux takes as one comma-separated list. */
    public List<String> clientFlags() {
        return clientFlags;
    }

    /** What the session's first pane runs, empty for the default shell. */
    public List<String> command() {
        return command;
    }

    /**
     * The command that makes this session, reporting it through {@code format}.
     *
     * <p>The version arrives as a supplier because this is the one creating call that may run when
     * there is no server yet, and asking a socket with nothing behind it for its version fails.
     * Nothing asks unless the spec depends on the answer.
     *
     * @param format the row format the caller will read the result back with
     * @param running what the server about to run this is, asked only if it matters
     * @throws UnsupportedTmuxVersion if the spec asks for something {@code running} does not have
     */
    List<String> argv(String format, Supplier<TmuxVersion> running) {
        if (size != null) {
            TmuxVersion version = running.get();
            if (!version.atLeast(SIZE_SINCE)) {
                throw new UnsupportedTmuxVersion("a size for a detached session", SIZE_SINCE, version);
            }
        }
        List<String> argv = new ArrayList<>(24);
        argv.add("new-session");
        // Always: without -d, tmux tries to attach and fails outright with no terminal.
        argv.add("-d");
        if (detachOthers) {
            argv.add("-D");
        }
        if (withoutSize) {
            argv.add("-X");
        }
        if (name != null) {
            argv.add("-s");
            argv.add(name);
        }
        if (windowName != null) {
            argv.add("-n");
            argv.add(windowName);
        }
        if (size != null) {
            argv.add("-x");
            argv.add(Integer.toString(size.width()));
            argv.add("-y");
            argv.add(Integer.toString(size.height()));
        }
        if (directory != null) {
            argv.add("-c");
            argv.add(directory.toString());
        }
        if (!clientFlags.isEmpty()) {
            // tmux reads -f as one comma-separated list, not as a flag that may repeat.
            argv.add("-f");
            argv.add(String.join(",", clientFlags));
        }
        for (Map.Entry<String, String> variable : environment.entrySet()) {
            argv.add("-e");
            argv.add(variable.getKey() + "=" + variable.getValue());
        }
        argv.add("-P");
        argv.add("-F");
        argv.add(format);
        // Last, because everything after the command belongs to the command.
        argv.addAll(command);
        return List.copyOf(argv);
    }

    @Override
    public String toString() {
        return "SessionSpec[" + (name == null ? "unnamed" : name) + (size == null ? "" : " " + size) + "]";
    }

    /** Collects the choices, each named for what it does rather than for the field it sets. */
    public static final class Builder {

        private @Nullable String name;
        private @Nullable Path directory;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private @Nullable String windowName;
        private @Nullable Dimensions size;
        private boolean withoutSize;
        private boolean detachOthers;
        private List<String> clientFlags = List.of();
        private List<String> command = List.of();

        private Builder() {}

        /** Names the session, where tmux would otherwise number it. */
        public Builder named(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** Names the session's first window. */
        public Builder firstWindowNamed(String windowName) {
            this.windowName = Objects.requireNonNull(windowName, "windowName");
            return this;
        }

        /** Runs a command instead of the default shell. */
        public Builder running(String... argv) {
            List<String> given = List.of(argv);
            if (given.isEmpty()) {
                throw new IllegalArgumentException("command is empty");
            }
            command = given;
            return this;
        }

        /** Starts the session in this directory. */
        public Builder in(Path directory) {
            this.directory = Objects.requireNonNull(directory, "directory");
            return this;
        }

        /** Sets one variable in the new session's environment. */
        public Builder env(String name, String value) {
            environment.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Adds several variables to the new session's environment, keeping any already set. */
        public Builder environment(Map<String, String> variables) {
            variables.forEach(this::env);
            return this;
        }

        /** Gives the session a size, which a detached session has no client to take one from. */
        public Builder sized(Dimensions size) {
            this.size = Objects.requireNonNull(size, "size");
            return this;
        }

        /** Asks tmux not to give the session a size at all. */
        public Builder withoutSize() {
            withoutSize = true;
            return this;
        }

        /** Detaches any other client already on this session. */
        public Builder detachOthers() {
            detachOthers = true;
            return this;
        }

        /** Sets the client flags tmux applies, which it reads as one comma-separated list. */
        public Builder clientFlags(String... flags) {
            clientFlags = List.of(flags);
            return this;
        }

        /** The finished spec. */
        public SessionSpec build() {
            return new SessionSpec(this);
        }
    }
}
