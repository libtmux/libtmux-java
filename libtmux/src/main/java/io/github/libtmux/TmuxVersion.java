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
 * <p>Between releases, tmux reports itself as {@code next-M.m}: a development build tracking toward
 * the release named, not that release itself. It has everything the previous release shipped and
 * nothing later's patches guarantee, so it sorts strictly between the two — above every patch of the
 * release before it, below the release it names and every patch of that.
 *
 * <p>A release candidate, {@code 3.8-rc}, compares equal to the release it names. tmux freezes
 * features at the candidate, so it has everything the release will and differs only in fixes —
 * measured, not assumed: a 3.8 candidate writes JSON layouts and empties a dead pane's pid exactly
 * as 3.8 does, and a feature gated on 3.8 works on it. It is not {@code equals} to the release, and
 * it is written back exactly as tmux wrote it, which matters beyond display: every capture is
 * fenced on the version tmux reports, and a candidate printed as {@code 3.8} failed that fence on
 * every read.
 *
 * @param major the major number
 * @param minor the minor number
 * @param patch the patch letter, or empty for an unlettered release
 * @param development whether this is a {@code next-M.m} build rather than the release {@code M.m}
 *     itself
 * @param preRelease the release candidate this is, such as {@code rc} or {@code rc2}, or empty for a
 *     release
 */
public record TmuxVersion(int major, int minor, String patch, boolean development, String preRelease)
        implements Comparable<TmuxVersion> {

    private static final Pattern RELEASE = Pattern.compile("^(next-)?(\\d+)\\.(\\d+)([a-z]*)(?:-(rc\\d*))?");

    public TmuxVersion {
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(preRelease, "preRelease");
    }

    /** A release or a development build, without a release candidate. */
    public TmuxVersion(int major, int minor, String patch, boolean development) {
        this(major, minor, patch, development, "");
    }

    /** A released version — the ordinary case, and every version literal this library names. */
    public TmuxVersion(int major, int minor, String patch) {
        this(major, minor, patch, false, "");
    }

    /**
     * Reads what tmux reports for {@code #{version}}.
     *
     * @throws IllegalArgumentException if the text does not begin with a release number, optionally
     *     preceded by {@code next-}; a build that reports something else is one this cannot make
     *     version decisions about
     */
    public static TmuxVersion parse(String reported) {
        Matcher release = RELEASE.matcher(reported);
        if (!release.find()) {
            throw new IllegalArgumentException("not a tmux version: " + reported);
        }
        return new TmuxVersion(
                Integer.parseInt(release.group(2)),
                Integer.parseInt(release.group(3)),
                release.group(4),
                release.group(1) != null,
                release.group(5) == null ? "" : release.group(5));
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
        if (byMinor != 0) {
            return byMinor;
        }
        if (development != other.development) {
            // A next-M.m build has not shipped everything M.m's own patches will carry, so it
            // sorts below the release it names, whatever either side's patch letter is. A release
            // candidate is not ordered apart from its release at all: features are frozen at it.
            return development ? -1 : 1;
        }
        // An unlettered release precedes its own patches, which empty-string ordering already gives.
        return patch.compareTo(other.patch);
    }

    @Override
    public String toString() {
        return (development ? "next-" : "") + major + "." + minor + patch
                + (preRelease.isEmpty() ? "" : "-" + preRelease);
    }
}
