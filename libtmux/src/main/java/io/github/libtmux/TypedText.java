package io.github.libtmux;

import java.util.List;
import java.util.Objects;

/**
 * What this library recently typed into a pane, so a wait can tell the pane's answer from its own
 * question.
 *
 * <p>A terminal echoes what is typed at it. A caller that sends a command and then watches for
 * something the command's own text contains — {@code sendLine("./build --target release")} followed
 * by a wait for {@code release} — would otherwise be answered by the echo, immediately, before the
 * command has done anything. No capture can recognise an echo on its own: the only party that knows
 * what was typed is whoever typed it.
 *
 * <p>A snapshot, taken once, on purpose. What a pane is holding ages out, so a watcher that asked
 * again on every look would stop discounting the echo partway through — and a command slow enough
 * to be worth waiting for is exactly one that outlives the record. Take this when the watch starts
 * and use the same one for every look it makes.
 *
 * <pre>{@code
 * TypedText typed = TypedText.in(pane);
 * while (watching) {
 *     List<String> answer = typed.withoutEcho(pane.capture());
 * }
 * }</pre>
 */
public final class TypedText {

    private final List<String> typed;

    private TypedText(List<String> typed) {
        this.typed = typed;
    }

    /** What this library has typed into the pane recently enough to still be on its screen. */
    public static TypedText in(Pane pane) {
        Objects.requireNonNull(pane, "pane");
        return new TypedText(pane.server().echo().recentFor(pane.id()));
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
     * broken across rows.
     */
    public List<String> withoutEcho(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        return PaneEcho.withoutEcho(lines, typed);
    }

    /** Whether anything was typed into the pane recently enough to still be discounted. */
    public boolean isEmpty() {
        return typed.isEmpty();
    }

    @Override
    public String toString() {
        return "TypedText" + typed;
    }
}
