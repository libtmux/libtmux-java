package io.github.libtmux.tools.mcpswap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

record PathRoute(
        Path logical,
        Path target,
        Path physicalParent,
        Path anchor,
        String anchorIdentity,
        boolean exists,
        boolean symbolicLink,
        String linkTarget,
        String linkIdentity) {
    static PathRoute inspect(Path path) throws IOException {
        var logical = path.toAbsolutePath().normalize();
        var exists = Files.exists(logical, LinkOption.NOFOLLOW_LINKS);
        if (!exists) {
            var prospective = prospectiveTarget(logical);
            return new PathRoute(
                    logical,
                    prospective.target(),
                    parent(prospective.target()),
                    prospective.anchor(),
                    directoryIdentity(prospective.anchor()),
                    false,
                    false,
                    "",
                    "");
        }
        var attributes = Files.readAttributes(logical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            var link = Files.readSymbolicLink(logical).toString();
            var target = logical.toRealPath();
            requireRegular(target);
            var physicalParent = parent(target);
            return new PathRoute(
                    logical,
                    target,
                    physicalParent,
                    physicalParent,
                    directoryIdentity(physicalParent),
                    true,
                    true,
                    link,
                    String.valueOf(attributes.fileKey()));
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("path is not a regular file: " + logical);
        }
        var target = logical.toRealPath();
        var physicalParent = parent(target);
        return new PathRoute(
                logical,
                target,
                physicalParent,
                physicalParent,
                directoryIdentity(physicalParent),
                true,
                false,
                "",
                "");
    }

    void verify() throws IOException {
        if (!equals(inspect(logical))) {
            throw new IOException("path route changed: " + logical);
        }
    }

    private static ProspectiveTarget prospectiveTarget(Path logical) throws IOException {
        var ancestor = logical.getParent();
        if (ancestor == null) {
            throw new IOException("path has no parent: " + logical);
        }
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
            if (ancestor == null) {
                throw new IOException("path has no existing ancestor: " + logical);
            }
        }
        if (!Files.isDirectory(ancestor)) {
            throw new IOException("path ancestor is not a directory: " + ancestor);
        }
        var relative = ancestor.relativize(logical);
        var physicalAncestor = ancestor.toRealPath();
        return new ProspectiveTarget(physicalAncestor.resolve(relative).normalize(), physicalAncestor);
    }

    private static void requireRegular(Path target) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("symbolic link target is not a regular file: " + target);
        }
    }

    private static Path parent(Path target) throws IOException {
        var parent = target.getParent();
        if (parent == null) {
            throw new IOException("path has no parent: " + target);
        }
        return parent;
    }

    private static String directoryIdentity(Path directory) throws IOException {
        var attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.fileKey() == null) {
            throw new IOException("directory identity is unavailable: " + directory);
        }
        return attributes.fileKey().toString();
    }

    private record ProspectiveTarget(Path target, Path anchor) {}
}
