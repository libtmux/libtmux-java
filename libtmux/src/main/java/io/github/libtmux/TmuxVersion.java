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
 * <p>A release candidate, {@code 3.8-rc}, orders below the release it names ({@code next-3.8 <
 * 3.8-rc < 3.8-rc2 < 3.8 < 3.8a}), so {@link #compareTo} agrees with {@link #equals}. tmux freezes
 * features at the candidate, so a feature gate asks {@link #atLeast}, which counts a candidate as its
 * release: a 3.8 candidate writes JSON layouts and empties a dead pane's pid exactly as 3.8 does. A
 * gate on one release's own bug asks {@link #sameRelease}. A support floor names a release and uses
 * the strict order. The version is written back exactly as tmux wrote it, which matters beyond
 * display: every capture is fenced on the version tmux reports, and a candidate printed as {@code
 * 3.8} failed that fence on every read.
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

    private static final Pattern RELEASE = Pattern.compile("(next-)?(\\d+)\\.(\\d+)([a-z]?)(?:-(rc\\d*))?");

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
        if (!release.matches()) {
            throw new IllegalArgumentException("not a tmux version: " + reported);
        }
        return new TmuxVersion(
                Integer.parseInt(release.group(2)),
                Integer.parseInt(release.group(3)),
                release.group(4),
                release.group(1) != null,
                release.group(5) == null ? "" : release.group(5));
    }

    /**
     * Whether this version has everything the given one has.
     *
     * <p>A release candidate counts as its release here, because a feature gate asks what the build
     * ships. That is the one place this differs from {@link #compareTo}, which orders a candidate
     * below its release.
     */
    public boolean atLeast(TmuxVersion other) {
        return compare(other, false) >= 0;
    }

    /**
     * Whether this is the named release, a release candidate for it included. {@code next-} builds
     * and other releases are not. For a gate on one release's own bug.
     */
    public boolean sameRelease(TmuxVersion other) {
        return major == other.major
                && minor == other.minor
                && patch.equals(other.patch)
                && !development
                && !other.development;
    }

    /** {@code next-X.Y < X.Y-rc < X.Y-rcN < X.Y < X.Ya}, so it agrees with {@link #equals}. */
    @Override
    public int compareTo(TmuxVersion other) {
        return compare(other, true);
    }

    private int compare(TmuxVersion other, boolean candidates) {
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
            // sorts below the release it names, whatever either side's patch letter is.
            return development ? -1 : 1;
        }
        // An unlettered release precedes its own patches, which empty-string ordering already gives.
        int byPatch = patch.compareTo(other.patch);
        if (byPatch != 0 || !candidates) {
            return byPatch;
        }
        return Integer.compare(candidate(preRelease), candidate(other.preRelease));
    }

    /** A release outranks every candidate; a bare {@code rc} precedes {@code rc1}, {@code rc2}, ... */
    private static int candidate(String preRelease) {
        if (preRelease.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            return preRelease.length() <= 2 ? 0 : Math.max(1, Integer.parseInt(preRelease.substring(2)));
        } catch (NumberFormatException unnumbered) {
            return 0;
        }
    }

    @Override
    public String toString() {
        return (development ? "next-" : "") + major + "." + minor + patch
                + (preRelease.isEmpty() ? "" : "-" + preRelease);
    }
}
