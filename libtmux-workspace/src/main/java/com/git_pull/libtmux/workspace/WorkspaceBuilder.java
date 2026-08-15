package com.git_pull.libtmux.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.git_pull.libtmux.Layouts;
import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.Window;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds a tmux session from a written description.
 *
 * <p>Reads the shape of a tmuxp workspace file. Full runtime compatibility with tmuxp is not the
 * aim; starting from a file somebody already has is.
 *
 * <p>Reading and building are separate. A file that describes something tmux would refuse is
 * rejected while it is still text, before any session exists to leave half-built.
 */
public final class WorkspaceBuilder {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private WorkspaceBuilder() {}

    /** Reads a workspace from a file. */
    public static Workspace read(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the workspace file", e);
        }
    }

    /**
     * Reads a workspace from YAML text.
     *
     * @throws IllegalArgumentException if the description is one tmux could not build, including a
     *     layout name tmux would not recognise
     */
    public static Workspace parse(String yaml) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (IOException e) {
            throw new IllegalArgumentException("the workspace is not readable YAML", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("the workspace is not a mapping");
        }
        List<WindowSpec> windows = new ArrayList<>();
        for (JsonNode window : root.path("windows")) {
            windows.add(window(window));
        }
        return new Workspace(root.path("session_name").asText(""), windows);
    }

    private static WindowSpec window(JsonNode window) {
        String name = window.path("window_name").asText("");
        JsonNode layout = window.get("layout");
        List<PaneSpec> panes = new ArrayList<>();
        for (JsonNode pane : window.path("panes")) {
            panes.add(pane(pane));
        }
        if (panes.isEmpty()) {
            // tmux cannot make a window without a pane, so an unstated pane means the default one.
            panes.add(new PaneSpec(List.of()));
        }
        return new WindowSpec(
                name,
                layout == null || layout.isNull() ? Optional.empty() : Optional.of(Layouts.require(layout.asText())),
                panes);
    }

    /** A pane is a bare command, a list of commands, or a mapping carrying {@code shell_command}. */
    private static PaneSpec pane(JsonNode pane) {
        if (pane.isTextual()) {
            return new PaneSpec(List.of(pane.asText()));
        }
        if (pane.isArray()) {
            return new PaneSpec(texts(pane));
        }
        JsonNode command = pane.path("shell_command");
        if (command.isTextual()) {
            return new PaneSpec(List.of(command.asText()));
        }
        return new PaneSpec(texts(command));
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            values.add(
                    element.isTextual()
                            ? element.asText()
                            : element.path("shell_command").asText(""));
        }
        return values;
    }

    /**
     * Creates the described session on a server.
     *
     * @return the session, from a capture taken once everything exists
     */
    public static Session build(Server server, Workspace workspace) {
        server.run(List.of("new-session", "-d", "-s", workspace.sessionName()));
        Session session = server.sessions().stream()
                .filter(candidate -> candidate.name().equals(workspace.sessionName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the session just created is not there"));

        List<WindowSpec> windows = workspace.windows();
        for (int index = 0; index < windows.size(); index++) {
            WindowSpec spec = windows.get(index);
            // tmux made the first window with the session, so it is renamed rather than added.
            Window window = index == 0
                    ? session.refresh().windows().get(0).rename(spec.name())
                    : session.refresh().newWindow(spec.name());
            fill(window, spec);
        }
        return session.refresh();
    }

    private static void fill(Window window, WindowSpec spec) {
        Window built = window;
        for (int index = 1; index < spec.panes().size(); index++) {
            built.split();
            built = built.refresh();
        }
        Window arranged = built;
        spec.layout()
                .ifPresent(layout -> arranged.server()
                        .run(List.of("select-layout", "-t", arranged.id().value(), layout)));

        List<Pane> panes = arranged.refresh().panes();
        for (int index = 0; index < spec.panes().size() && index < panes.size(); index++) {
            for (String command : spec.panes().get(index).commands()) {
                panes.get(index).sendLine(command);
            }
        }
    }
}
