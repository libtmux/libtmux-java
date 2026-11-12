package io.github.libtmux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What {@link Server#start(ServerLaunch)} needs to start a private tmux server.
 *
 * <p>A builder-built class rather than a record, for the reason {@link ServerConfig} is one: tmux
 * keeps growing options, and a record's constructor is public API.
 */
public final class ServerLaunch {

    private static final Path DEFAULT_ROOT = Path.of("/tmp/libtmux-java-dev");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final String binary;
    private final @Nullable Path configFile;
    private final Path root;
    private final Duration timeout;

    private ServerLaunch(Builder builder) {
        this.binary = builder.binary;
        this.configFile = builder.configFile;
        this.root = builder.root;
        this.timeout = builder.timeout;
    }

    /** A builder holding the documented defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** The tmux executable. */
    public String binary() {
        return binary;
    }

    /** The config file the server reads, or empty for none, in which case it reads an empty one. */
    public java.util.Optional<Path> configFile() {
        return java.util.Optional.ofNullable(configFile);
    }

    /** The directory each started server makes its own directory under. */
    public Path root() {
        return root;
    }

    /** How long start waits for the server to answer, and close waits for it to exit. */
    public Duration timeout() {
        return timeout;
    }

    /** Collects the choices; {@link #build()} checks them. */
    public static final class Builder {

        private String binary = "tmux";
        private @Nullable Path configFile;
        private Path root = DEFAULT_ROOT;
        private Duration timeout = DEFAULT_TIMEOUT;

        private Builder() {}

        /** Sets the tmux executable, resolved on {@code PATH} unless it is an absolute path. */
        public Builder binary(String binary) {
            this.binary = Objects.requireNonNull(binary, "binary");
            return this;
        }

        /** Sets the config file the server reads; without one it reads an empty file. */
        public Builder configFile(Path configFile) {
            this.configFile = Objects.requireNonNull(configFile, "configFile");
            return this;
        }

        /**
         * Sets the directory servers make their own directory under, {@code /tmp/libtmux-java-dev}
         * by default. Keep it short: a socket path longer than the platform's {@code sun_path} does
         * not work.
         *
         * <p>A server whose JVM was killed before it could close is ended by the next start under
         * the same root, which only ever touches a directory whose owner is gone.
         */
        public Builder root(Path root) {
            this.root = Objects.requireNonNull(root, "root");
            return this;
        }

        /** Sets how long start waits for the server to answer, and close waits for it to exit. */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout is not positive");
            }
            this.timeout = timeout;
            return this;
        }

        /** The launch these choices describe. */
        public ServerLaunch build() {
            return new ServerLaunch(this);
        }
    }
}
