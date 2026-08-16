package io.github.libtmux.mcp;

import io.github.libtmux.Layout;
import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Making, arranging and ending the things a model works in.
 *
 * <p>Anything that ends something a person cannot get back asks about the pane this conversation
 * runs through first. A model told to "clean up the windows we are done with" has no way of knowing
 * that one of them is the one it is speaking through, and finding out by doing it is not recoverable.
 */
final class Shaping {

    private Shaping() {}

    record Made(String kind, String id, @Nullable String paneId, String name, String note) {}

    record Changed(
            String kind, String id, String what, @Nullable String note) {}

    record Ended(String kind, String id, @Nullable String note) {}

    static Made newSession(Call call) {
        String name = call.string("name");
        Server server = call.server();
        if (server.hasSession(name)) {
            throw new IllegalArgumentException(
                    "a session named '" + name + "' is already there; pick another name, or use it as it is");
        }
        Session session = server.newSession(spec -> {
            spec.named(name);
            call.maybe("path").ifPresent(path -> spec.in(Path.of(path)));
            call.maybe("command").ifPresent(command -> spec.running("sh", "-c", command));
        });
        Pane first = session.windows().get(0).panes().get(0);
        return new Made(
                "session",
                session.id().value(),
                first.id().value(),
                session.name(),
                "Detached, so nothing is watching it. Its first pane is the one to act on.");
    }

    static Made newWindow(Call call) {
        Session session = Targets.session(call.server(), call.string("session"));
        Window window = session.newWindow(spec -> {
            call.maybe("name").ifPresent(spec::named);
            call.maybe("path").ifPresent(path -> spec.in(Path.of(path)));
            call.maybe("command").ifPresent(command -> spec.running("sh", "-c", command));
            spec.detached();
        });
        Pane first = window.panes().get(0);
        return new Made(
                "window",
                window.id().value(),
                first.id().value(),
                window.name(),
                "Made without switching to it, so whatever a person was looking at is still there.");
    }

    /**
     * Splits a pane, giving the new one back.
     *
     * <p>The direction says where the new pane goes, which is the way a person describes it. tmux's
     * own flags say which way the split runs, and the two are easy to state backwards.
     */
    static Made splitPane(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        String direction = call.maybe("direction").orElse("below").toLowerCase(Locale.ROOT);
        Pane made = pane.split(spec -> {
            switch (direction) {
                case "below", "down" -> spec.below();
                case "above", "up" -> spec.above();
                case "right" -> spec.toRight();
                case "left" -> spec.toLeft();
                default ->
                    throw new IllegalArgumentException(
                            "'" + direction + "' is not a direction; use below, above, left or right");
            }
            int percent = call.integer("percent", 0);
            if (percent > 0) {
                spec.percent(Math.clamp(percent, 1, 99));
            }
            call.maybe("path").ifPresent(path -> spec.in(Path.of(path)));
            call.maybe("command").ifPresent(command -> spec.running("sh", "-c", command));
        });
        return new Made(
                "pane",
                made.id().value(),
                made.id().value(),
                made.window().name(),
                "The new pane is " + made.id().value() + "; " + pane.id().value() + " is still there.");
    }

    static Changed rename(Call call) {
        String target = call.string("target");
        String name = call.string("name");
        if (target.startsWith("@")) {
            Window window = Targets.window(call.server(), target);
            window.rename(name);
            return new Changed("window", target, name, null);
        }
        Session session = Targets.session(call.server(), target);
        session.rename(name);
        return new Changed("session", session.id().value(), name, null);
    }

    static Changed select(Call call) {
        String target = call.string("target");
        if (target.startsWith("%")) {
            Pane pane = Targets.pane(call.server(), target);
            pane.select();
            return new Changed("pane", target, "active", "A person attached to this session now sees it.");
        }
        Window window = Targets.window(call.server(), target);
        window.select();
        return new Changed("window", target, "active", "A person attached to this session now sees it.");
    }

    static Changed selectLayout(Call call) {
        Window window = Targets.window(call.server(), call.string("window_id"));
        String asked = call.string("layout");
        Layout layout = layoutNamed(asked)
                .orElseThrow(() ->
                        new IllegalArgumentException("'" + asked + "' is not a layout; use one of " + layoutNames()));
        window.selectLayout(layout);
        return new Changed("window", window.id().value(), layout.name(), null);
    }

    static Changed resizePane(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        int width = call.integer("width", 0);
        int height = call.integer("height", 0);
        if (width <= 0 && height <= 0) {
            throw new IllegalArgumentException("give 'width', 'height' or both, in cells");
        }
        var size = pane.size();
        pane.resizeTo(new io.github.libtmux.Dimensions(
                width > 0 ? width : size.width(), height > 0 ? height : size.height()));
        var now = pane.refresh().size();
        return new Changed(
                "pane",
                pane.id().value(),
                now.width() + "x" + now.height(),
                now.width() == size.width() && now.height() == size.height()
                        ? "tmux did not change the size: a pane cannot grow past its window, and its "
                                + "neighbours have to give up the cells it takes."
                        : null);
    }

    static Ended kill(Call call) {
        String target = call.string("target");
        boolean confirmed = call.flag("confirm_self", false);
        Server server = call.server();
        if (target.startsWith("%")) {
            Pane pane = Targets.pane(server, target);
            guard(call, List.of(pane), confirmed, "pane");
            pane.kill();
            return new Ended("pane", target, null);
        }
        if (target.startsWith("@")) {
            Window window = Targets.window(server, target);
            guard(call, window.panes(), confirmed, "window");
            window.kill();
            return new Ended("window", target, null);
        }
        if ("server".equals(target)) {
            guard(call, server.panes(), confirmed, "server");
            server.killServer();
            return new Ended(
                    "server",
                    "server",
                    "Every session on it is gone, and so is this connection's "
                            + "server. Nothing else in this conversation can act on it.");
        }
        Session session = Targets.session(server, target);
        guard(
                call,
                session.windows().stream()
                        .flatMap(window -> window.panes().stream())
                        .toList(),
                confirmed,
                "session");
        session.kill();
        return new Ended("session", session.id().value(), null);
    }

    /**
     * Refuses to end the pane this conversation is coming through, unless told plainly to.
     *
     * <p>Not a confirmation prompt: a model cannot be asked. It is a second, explicit argument, so
     * ending the conversation's own pane has to be the thing that was meant rather than the thing
     * that happened.
     */
    private static void guard(Call call, List<Pane> going, boolean confirmed, String kind) {
        if (confirmed) {
            return;
        }
        Optional<PaneId> mine = call.caller().pane();
        if (mine.isEmpty() || going.stream().noneMatch(pane -> call.caller().isSelf(pane.id()))) {
            return;
        }
        List<String> others = going.stream()
                .map(pane -> pane.id().value())
                .filter(id -> !id.equals(mine.get().value()))
                .toList();
        throw new IllegalStateException(refusal(kind, mine.get().value(), others));
    }

    /**
     * Leads with what can be ended, because that is what gets acted on.
     *
     * <p>An earlier wording offered {@code confirm_self=true} as the next step. A model told to tidy
     * up a session read that as the way to finish the job, passed it, and killed the server it was
     * speaking through — so the override now comes last and says what it costs.
     */
    private static String refusal(String kind, String mine, List<String> others) {
        String instead = others.isEmpty()
                ? "Nothing else is in it, so there is nothing here to end safely."
                : "End these instead, and leave " + mine + " alone: " + String.join(", ", others) + ".";
        return "Refused. " + ("pane".equals(kind) ? "That is" : "That " + kind + " holds") + " pane " + mine
                + ", which this MCP server is running in, so ending it cuts this conversation off from tmux "
                + "— every later tmux call fails, including the ones that would say why. " + instead
                + " Pass confirm_self=true only if disconnecting yourself is the actual goal.";
    }

    /** tmux names a layout with hyphens; the enum names it with underscores. */
    static Optional<Layout> layoutNamed(String name) {
        String wanted = name.toUpperCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(Layout.values())
                .filter(candidate -> candidate.name().equals(wanted))
                .findFirst();
    }

    static List<String> layoutNames() {
        return Arrays.stream(Layout.values())
                .map(value -> value.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .toList();
    }
}
