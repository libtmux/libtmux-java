package io.github.libtmux;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A released tmux version, ordered the way tmux releases are.
 *
 * <p>tmux numbers a patch release with a letter rather than a third number: 3.7a follows 3.7, and
 * 3.3a follows 3.3. Comparing the text alone gets that wrong in both directions, and a feature gate
 * that gets it wrong reads a format the running tmux does not have.
 *
 * <p>Between releases, tmux reports itself as {@code next-M.m}: a development build tracking toward
 * the release named, not that release itself. It has everything the previous release shipped and
 * nothing later's patches guarantee, so it sorts strictly between the two — above every patch of the
 * release before it, below the release it names and every patch of that.
 *
 * <p>A release candidate, {@code X.Y-rc}, orders below the release it names ({@code next-X.Y <
 * X.Y-rc < X.Y-rc2 < X.Y < X.Ya}), so {@link #compareTo} agrees with {@link #equals}, and {@link
 * #atLeast} follows the same order. A gate names the release that ships the behaviour, such as
 * {@code 3.8} for JSON layouts. A gate on one release's own bug asks {@link #sameRelease}. The
 * version is written back exactly as tmux wrote it, which matters beyond display: every capture is
 * fenced on the version tmux reports, and a version printed in any other spelling fails that fence
 * on every read.
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

    // Each number is bounded to four digits, so no input overflows an int or ranks oddly: rc0 does
    // not exist, and an rc number past four digits is not a tmux report.
    private static final String NUMBER = "(0|[1-9]\\d{0,3})";
    private static final Pattern RELEASE =
            Pattern.compile("(next-)?" + NUMBER + "\\." + NUMBER + "([a-z]?)(?:-(rc(?:[1-9]\\d{0,3})?))?");

    /** The oldest tmux this library supports; an older one is refused before any request is sent. */
    public static final TmuxVersion MINIMUM = new TmuxVersion(3, 3, "a");

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
        // tmux reports a development build as next-M.m only: no patch letter, no candidate.
        if (!release.matches()
                || (release.group(1) != null && (!release.group(4).isEmpty() || release.group(5) != null))) {
            throw new IllegalArgumentException("not a tmux version: " + reported);
        }
        return new TmuxVersion(
                Integer.parseInt(release.group(2)),
                Integer.parseInt(release.group(3)),
                release.group(4),
                release.group(1) != null,
                release.group(5) == null ? "" : release.group(5));
    }

    /** Whether this version ranks at or above the given one in {@link #compareTo}'s order. */
    public boolean atLeast(TmuxVersion other) {
        return compareTo(other) >= 0;
    }

    /**
     * Whether this is the named release, a release candidate for it included. {@code next-} builds
     * and other releases are not. For a gate on one release's own bug.
     *
     * <p>A suffixed build counts as its release: the gated bugs were measured on the release itself,
     * and leaving the suffixed build out would skip the workaround on a build that very likely needs
     * it.
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
        if (byPatch != 0) {
            return byPatch;
        }
        return Integer.compare(candidate(preRelease), candidate(other.preRelease));
    }

    /** A release outranks every candidate; a bare {@code rc} precedes {@code rc1}, {@code rc2}, ... */
    private static int candidate(String preRelease) {
        if (preRelease.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return preRelease.length() <= 2 ? 0 : Integer.parseInt(preRelease.substring(2));
    }

    @Override
    public String toString() {
        return (development ? "next-" : "") + major + "." + minor + patch
                + (preRelease.isEmpty() ? "" : "-" + preRelease);
    }
}
