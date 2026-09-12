package io.github.libtmux.workspace.cli;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

final class Catalog {
    private static final List<String> EXTENSIONS = List.of("yaml", "yml", "json");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}|\\$([A-Za-z_][A-Za-z0-9_]*)");

    private Catalog() {}

    static Path home(Main.Context context) {
        return Path.of(context.environment().getOrDefault("HOME", System.getProperty("user.home")));
    }

    static String expand(Main.Context context, String value) {
        String text = value.equals("~")
                ? home(context).toString()
                : value.startsWith("~/")
                        ? home(context).resolve(value.substring(2)).toString()
                        : value;
        var matcher = VARIABLE.matcher(text);
        return matcher.replaceAll(match -> {
            String name = match.group(1) == null ? match.group(2) : match.group(1);
            return java.util.regex.Matcher.quoteReplacement(
                    context.environment().getOrDefault(name, match.group()));
        });
    }

    static String mask(Main.Context context, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path home = home(context).toAbsolutePath().normalize();
        return absolute.startsWith(home)
                ? "~" + (absolute.equals(home) ? "" : "/" + home.relativize(absolute))
                : absolute.toString();
    }

    private static Map<Path, String> roots(Main.Context context) {
        var roots = new LinkedHashMap<Path, String>();
        String configured = context.environment().getOrDefault("TMUXP_CONFIGDIR", "");
        if (!configured.isEmpty())
            roots.put(context.directory().resolve(expand(context, configured)), "$TMUXP_CONFIGDIR");
        String xdg = context.environment().getOrDefault("XDG_CONFIG_HOME", "");
        roots.put(
                (xdg.isEmpty() ? home(context).resolve(".config") : Path.of(expand(context, xdg))).resolve("tmuxp"),
                xdg.isEmpty() ? "XDG default" : "$XDG_CONFIG_HOME/tmuxp");
        roots.put(home(context).resolve(".tmuxp"), "Legacy");
        return roots;
    }

    private static Path active(Main.Context context) {
        return roots(context).keySet().stream()
                .filter(Files::isDirectory)
                .findFirst()
                .orElse(home(context).resolve(".tmuxp"));
    }

    static ArrayNode directories(Main.Context context) throws IOException {
        ArrayNode result = Documents.JSON.createArrayNode();
        Path active = active(context);
        for (var entry : roots(context).entrySet()) {
            boolean exists = Files.isDirectory(entry.getKey());
            result.addObject()
                    .put("path", mask(context, entry.getKey()))
                    .put("source", entry.getValue())
                    .put("exists", exists)
                    .put("workspace_count", exists ? globalFiles(entry.getKey()).size() : 0)
                    .put("active", exists && active.equals(entry.getKey()));
        }
        return result;
    }

    private static List<Path> globalFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> EXTENSIONS.contains(extension(path)))
                    .sorted()
                    .toList();
        }
    }

    static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Optional<Path> project(Path directory) {
        return EXTENSIONS.stream()
                .map(ext -> directory.resolve(".tmuxp." + ext))
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private static Optional<Path> candidate(Path path) {
        if (Files.isRegularFile(path)) return Optional.of(path);
        if (Files.isDirectory(path)) return project(path);
        if (!extension(path).isEmpty()) return Optional.empty();
        return EXTENSIONS.stream()
                .map(ext -> path.resolveSibling(path.getFileName() + "." + ext))
                .filter(Files::isRegularFile)
                .findFirst();
    }

    static Path resolve(Main.Context context, String name, String importer) throws IOException {
        Path path = Path.of(expand(context, name));
        boolean bare = path.getNameCount() == 1 && !name.startsWith(".") && !name.contains("/");
        Path global =
                switch (importer) {
                    case "teamocil" -> home(context).resolve(".teamocil");
                    case "tmuxinator" ->
                        Path.of(expand(
                                context, context.environment().getOrDefault("TMUXINATOR_CONFIG", "~/.tmuxinator")));
                    default -> active(context);
                };
        Optional<Path> found = bare && extension(path).isEmpty() ? candidate(global.resolve(path)) : Optional.empty();
        if (found.isEmpty()) found = candidate(context.directory().resolve(path));
        if (found.isEmpty() && bare) found = candidate(global.resolve(path));
        return found.orElseThrow(() -> new Main.Failure("not_found", 1, "workspace source was not found: " + name))
                .toRealPath();
    }

    static ArrayNode records(Main.Context context, boolean full) throws IOException {
        var paths = new LinkedHashMap<Path, String>();
        for (Path directory = context.directory(); directory != null; directory = directory.getParent()) {
            project(directory).ifPresent(path -> paths.put(path, "local"));
            if (directory.equals(home(context))) break;
        }
        for (Path path : globalFiles(active(context))) paths.putIfAbsent(path, "global");
        ArrayNode records = Documents.JSON.createArrayNode();
        for (var entry : paths.entrySet()) {
            Path path = entry.getKey();
            ObjectNode record = records.addObject()
                    .put("name", Documents.stem(path))
                    .put("path", mask(context, path))
                    .put("format", extension(path))
                    .put("source", entry.getValue())
                    .put("size", Files.size(path))
                    .put("mtime", Files.getLastModifiedTime(path).toInstant().toString());
            try {
                ObjectNode config = Documents.read(path);
                record.set("session_name", config.path("session_name"));
                if (full) record.set("config", config);
            } catch (IOException | IllegalArgumentException failure) {
                record.put("error", String.valueOf(failure.getMessage()));
            }
        }
        return records;
    }
}
