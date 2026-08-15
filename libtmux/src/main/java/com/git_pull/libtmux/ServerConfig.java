package com.git_pull.libtmux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a server needs to know before it runs anything: which tmux, which server, which config.
 *
 * <p>Deliberately a builder-built class rather than a record. tmux keeps growing flags, and a
 * record's canonical constructor is public API: every field added later would break both source and
 * binary compatibility for anyone who had constructed one.
 *
 * <p>Nothing here contacts tmux. A config can be built, stored and compared on a machine that has
 * no tmux installed.
 */
public final class ServerConfig {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String binary;
    private final ServerEndpoint endpoint;
    private final @Nullable Path configFile;
    private final Duration defaultTimeout;
    private final ExecutionMode mode;

    private ServerConfig(Builder builder, ExecutionMode mode) {
        this.binary = builder.binary;
        this.endpoint = builder.endpoint;
        this.configFile = builder.configFile;
        this.defaultTimeout = builder.defaultTimeout;
        this.mode = mode;
    }

    /** A builder holding the documented defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** The tmux executable, resolved on {@code PATH} unless it is an absolute path. */
    public String binary() {
        return binary;
    }

    /** Which tmux server to talk to. */
    public ServerEndpoint endpoint() {
        return endpoint;
    }

    /** The config file tmux should read, if the caller pinned one. */
    public Optional<Path> configFile() {
        return Optional.ofNullable(configFile);
    }

    /** How long a request waits when the caller does not say. */
    public Duration defaultTimeout() {
        return defaultTimeout;
    }

    /**
     * How commands reach tmux. Changes the carrying, never the meaning.
     *
     * <p>Decided rather than merely requested: a config that named no mode reports the one
     * {@code -Dlibtmux.mode} or {@code LIBTMUX_MODE} chose for it, so this is what
     * {@link Server#open} will build. A server handed a transport by {@link Server#using} is
     * carried by that transport whatever this says.
     */
    public ExecutionMode mode() {
        return mode;
    }

    /**
     * The argv prefix every command on this server begins with: the binary, the server selection,
     * and the config file if one was pinned.
     */
    public List<String> endpointCommand() {
        List<String> command = new ArrayList<>(6);
        command.add(binary);
        command.addAll(endpoint.flags());
        if (configFile != null) {
            command.add("-f");
            command.add(configFile.toString());
        }
        return Collections.unmodifiableList(command);
    }

    /** A builder holding every choice this config made. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.binary = binary;
        builder.endpoint = endpoint;
        builder.configFile = configFile;
        builder.defaultTimeout = defaultTimeout;
        builder.mode = mode;
        return builder;
    }

    /** Collects the choices; {@link #build()} checks them. */
    public static final class Builder {

        private String binary = "tmux";
        private ServerEndpoint endpoint = ServerEndpoint.defaultSocket();
        private @Nullable Path configFile;
        private Duration defaultTimeout = DEFAULT_TIMEOUT;
        // Null until something names a mode, which is what lets an unset one fall to the ambient
        // choice: a default of DIRECT here could not be told apart from a caller asking for DIRECT.
        private @Nullable ExecutionMode mode;

        private Builder() {}

        /** Sets the tmux executable. */
        public Builder binary(String binary) {
            this.binary = Objects.requireNonNull(binary, "binary");
            return this;
        }

        /** Sets which server to talk to. */
        public Builder endpoint(ServerEndpoint endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            return this;
        }

        /** Pins the config file tmux reads, which is how a test isolates itself from a user's own. */
        public Builder configFile(Path configFile) {
            this.configFile = Objects.requireNonNull(configFile, "configFile");
            return this;
        }

        /**
         * Chooses how commands reach tmux, and settles it: an ambient choice cannot override this.
         *
         * <p>Left unsaid, the mode comes from {@link ExecutionMode#of} and falls back to
         * {@link ExecutionMode#DIRECT}, which is what the tmux binary itself does.
         */
        public Builder mode(ExecutionMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /** Sets the deadline a request gets when the caller does not supply one. */
        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
            return this;
        }

        /**
         * Builds an immutable config, rejecting choices that could only fail later.
         *
         * <p>Reads {@code -Dlibtmux.mode} and {@code LIBTMUX_MODE} when nothing named a mode, so
         * the config carries a decided one from here on and nothing downstream consults them again.
         *
         * @throws IllegalArgumentException if a choice would only fail later, including a property
         *     or variable naming something that is not a mode
         */
        public ServerConfig build() {
            if (binary.isEmpty()) {
                throw new IllegalArgumentException("binary is empty");
            }
            if (defaultTimeout.isZero() || defaultTimeout.isNegative()) {
                throw new IllegalArgumentException("defaultTimeout is not positive");
            }
            ExecutionMode chosen = mode != null
                    ? mode
                    : ExecutionMode.of(System.getProperties(), System.getenv()).orElse(ExecutionMode.DIRECT);
            return new ServerConfig(this, chosen);
        }
    }
}
