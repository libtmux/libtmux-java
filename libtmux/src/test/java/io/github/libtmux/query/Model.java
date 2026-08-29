package io.github.libtmux.query;

import java.util.List;
import java.util.Optional;

/** A hand-written stand-in for what the annotation processor will generate. */
final class Model {

    record Pane(String id, String command, int index, boolean active) {}

    record Window(String id, String name, boolean active, List<Pane> panes, Optional<Pane> activePane) {}

    private Model() {}

    /** The generated shape: static, typed, one handle per field and relation. */
    static final class Pane_ {
        private static final Fields.TextField<Pane> COMMAND = Fields.text("command", Pane::command);
        private static final Fields.NumberField<Pane> INDEX = Fields.number("index", Pane::index);
        private static final Fields.FlagField<Pane> ACTIVE = Fields.flag("active", Pane::active);

        static Fields.TextField<Pane> command() {
            return COMMAND;
        }

        static Fields.NumberField<Pane> index() {
            return INDEX;
        }

        static Fields.FlagField<Pane> active() {
            return ACTIVE;
        }

        private Pane_() {}
    }

    static final class Window_ {
        private static final Fields.TextField<Window> NAME = Fields.text("name", Window::name);
        private static final Fields.ToManyRef<Window, Pane> PANES = Fields.toMany("panes", Window::panes);
        private static final Fields.ToOneRef<Window, Pane> ACTIVE_PANE = Fields.toOne("activePane", Window::activePane);

        static Fields.TextField<Window> name() {
            return NAME;
        }

        static Fields.ToManyRef<Window, Pane> panes() {
            return PANES;
        }

        static Fields.ToOneRef<Window, Pane> activePane() {
            return ACTIVE_PANE;
        }

        private Window_() {}
    }
}
