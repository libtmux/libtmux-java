package io.github.libtmux;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A released tmux version, ordered the way tmux releases are.
 *
 * <p>tmux numbers a patch release with a letter rather than a third number: 3.7a follows 3.7, and
 * 3.2a follows 3.2. Comparing the text alone gets that wrong in both directions, and a feature gate
 * that gets it wrong reads a format the running tmux does not have.
 *
 * @param major the major number
 * @param minor the minor number
 * @param patch the patch letter, or empty for an unlettered release
 */
public record TmuxVersion(int major, int minor, String patch) implements Comparable<TmuxVersion> {

    private static final Pattern RELEASE = Pattern.compile("^(\\d+)\\.(\\d+)([a-z]*)");

    public TmuxVersion {
        Objects.requireNonNull(patch, "patch");
    }

    /**
     * Reads what tmux reports for {@code #{version}}.
     *
     * @throws IllegalArgumentException if the text does not begin with a release number; a build
     *     that reports something else is one this cannot make version decisions about
     */
    public static TmuxVersion parse(String reported) {
        Matcher release = RELEASE.matcher(reported);
        if (!release.find()) {
            throw new IllegalArgumentException("not a tmux version: " + reported);
        }
        return new TmuxVersion(
                Integer.parseInt(release.group(1)), Integer.parseInt(release.group(2)), release.group(3));
    }

    /** Whether this version has everything the given one has. */
    public boolean atLeast(TmuxVersion other) {
        return compareTo(other) >= 0;
    }

    @Override
    public int compareTo(TmuxVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        // An unlettered release precedes its own patches, which empty-string ordering already gives.
        return byMinor != 0 ? byMinor : patch.compareTo(other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + patch;
    }
}
