package io.github.libtmux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Discounts input recorded by this library when reading a pane's output.
 *
 * <p>Create one instance per wait. Each read refreshes pending input and retains submitted echoes
 * already observed, even after the shared record expires. This mutable view is not thread-safe.
 */
public final class TypedText {

    private final Server server;
    private final ServerIdentity identity;
    private final PaneId paneId;

    /** Every finished line this watch has ever seen, growing for as long as this instance lives. */
    private final Set<String> recentSoFar = new LinkedHashSet<>();

    private TypedText(Server server, ServerIdentity identity, PaneId paneId) {
        this.server = server;
        this.identity = identity;
        this.paneId = paneId;
    }

    /** What this library has typed into the pane recently enough to still be on its screen. */
    public static TypedText in(Pane pane) {
        Objects.requireNonNull(pane, "pane");
        return new TypedText(pane.server(), pane.server().identity(pane.snapshot()), pane.id());
    }

    /**
     * The lines with each recorded echo taken out, wherever it stands as an echo does.
     *
     * <p>An occurrence is taken out only where it is not part of a longer word on either side. That is
     * what keeps the answer when the answer merely contains the question — {@code id} comes off
     * {@code $ id} and stays inside {@code uid=1000} — while still taking out every copy of an echo a
     * shell drew more than once, and one a shell drew something straight after. A row the echo shared
     * with real output keeps that output.
     *
     * <p>What no rule can do is tell an echo from output that repeats the whole typed line as a line
     * of its own. Wait for something the command prints rather than something it says.
     *
     * <p>The lines are scanned as one string, so an echo the terminal broke across rows is still
     * found. Pass lines with wrapping already rejoined when the text being watched for can itself be
     * broken across rows. A shell redraw or truncated capture that retains only part of an echo
     * cannot be recognized reliably.
     */
    public List<String> withoutEcho(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        PaneEcho.Live live = server.echo().liveFor(identity, paneId);
        recentSoFar.addAll(live.recent());
        if (recentSoFar.isEmpty() && live.pending().isEmpty()) {
            return lines;
        }
        List<String> discount =
                new ArrayList<>(recentSoFar.size() + live.pending().size());
        discount.addAll(recentSoFar);
        discount.addAll(live.pending());
        return PaneEcho.withoutEcho(lines, discount);
    }

    /** Whether anything typed into the pane is live enough, right now, to still be discounted. */
    public boolean isEmpty() {
        if (!recentSoFar.isEmpty()) {
            return false;
        }
        PaneEcho.Live live = server.echo().liveFor(identity, paneId);
        return live.pending().isEmpty() && live.recent().isEmpty();
    }

    @Override
    public String toString() {
        return "TypedText" + server.echo().liveFor(identity, paneId);
    }
}
