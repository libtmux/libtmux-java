package io.github.libtmux;

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

    private ServerConfig(Builder builder) {
        this.binary = builder.binary;
        this.endpoint = builder.endpoint;
        this.configFile = builder.configFile;
        this.defaultTimeout = builder.defaultTimeout;
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
        return builder;
    }

    /** Collects the choices; {@link #build()} checks them. */
    public static final class Builder {

        private String binary = "tmux";
        private ServerEndpoint endpoint = ServerEndpoint.defaultSocket();
        private @Nullable Path configFile;
        private Duration defaultTimeout = DEFAULT_TIMEOUT;

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

        /** Sets the deadline a request gets when the caller does not supply one. */
        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
            return this;
        }

        /**
         * Builds an immutable config, rejecting choices that could only fail later.
         *
         * @throws IllegalArgumentException if a choice would only fail later
         */
        public ServerConfig build() {
            if (binary.isEmpty()) {
                throw new IllegalArgumentException("binary is empty");
            }
            if (defaultTimeout.isZero() || defaultTimeout.isNegative()) {
                throw new IllegalArgumentException("defaultTimeout is not positive");
            }
            return new ServerConfig(this);
        }
    }
}
