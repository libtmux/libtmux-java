package io.github.libtmux.query;

import java.util.List;
import java.util.Optional;

/** A hand-written stand-in for what the annotation processor will generate. */
final class Model {

    record Pane(String id, String command, int index, boolean active) {}

    record Window(String id, String name, boolean active, List<Pane> panes, Optional<Pane> activePane) {}

    private Model() {}

    /** The generated shape: static, typed, one handle per field and relation. */
    static final class Pane_ extends EntityMetamodel {
        static Fields.TextField<Pane> command() {
            return text("command", Pane::command);
        }

        static Fields.NumberField<Pane> index() {
            return number("index", Pane::index);
        }

        static Fields.FlagField<Pane> active() {
            return flag("active", Pane::active);
        }

        private Pane_() {}
    }

    static final class Window_ extends EntityMetamodel {
        static Fields.TextField<Window> name() {
            return text("name", Window::name);
        }

        static Fields.ToManyRef<Window, Pane> panes() {
            return toMany("panes", Window::panes);
        }

        static Fields.ToOneRef<Window, Pane> activePane() {
            return toOne("activePane", Window::activePane);
        }

        private Window_() {}
    }
}
