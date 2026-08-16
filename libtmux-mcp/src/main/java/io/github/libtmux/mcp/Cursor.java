package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * A place in a pane's output that a caller has already read up to.
 *
 * <p>Watching a pane means asking repeatedly, and asking repeatedly is what makes an agent expensive:
 * the tenth look at a build log re-reads the nine screens it already paid for. A cursor turns that
 * into "what is new", which is almost always nothing or a few lines.
 *
 * <p>Opaque on purpose. What it holds is this server's business and may change; a caller that parsed
 * it would break, and a caller that invents one gets told to start again rather than handed the
 * wrong lines.
 *
 * @param paneId the pane this position belongs to, so a cursor cannot be used on another
 * @param absolute how many lines had ever been above the bottom of the screen when it was taken
 * @param anchor a digest of the last line already delivered, which is how loss is noticed
 */
record Cursor(String paneId, int absolute, String anchor) {

    private static final String VERSION = "1";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** Where a pane is now: everything down to its last written line has been seen. */
    static Cursor at(Pane pane, int history, List<String> seen) {
        String last = seen.isEmpty() ? "" : seen.get(seen.size() - 1);
        return new Cursor(pane.id().value(), history + seen.size(), digest(last));
    }

    String encode() {
        return ENCODER.encodeToString(
                (VERSION + "|" + paneId + "|" + absolute + "|" + anchor).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a cursor a caller sent back.
     *
     * @throws IllegalArgumentException naming the recovery, because a caller holding an unreadable
     *     cursor can always start again by asking without one
     */
    static Cursor decode(String encoded) {
        String plain;
        try {
            plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unreadable();
        }
        String[] parts = plain.split("\\|", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw unreadable();
        }
        try {
            return new Cursor(parts[1], Integer.parseInt(parts[2]), parts[3]);
        } catch (NumberFormatException e) {
            throw unreadable();
        }
    }

    private static IllegalArgumentException unreadable() {
        return new IllegalArgumentException(
                "that cursor is not one this server issued; omit 'cursor' to start from what the pane shows now");
    }

    /** Short enough to keep a cursor small, wide enough that a neighbouring line will not collide. */
    static String digest(String line) {
        return Integer.toHexString(line.hashCode());
    }
}
