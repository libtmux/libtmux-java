package io.github.libtmux.tools.mcpswap;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

final class AtomicChange {
    private final String role;
    private final PathRoute route;
    private FileSnapshot before;
    private final FileContent desired;
    private @Nullable Path stage;
    private @Nullable FileSnapshot staged;
    private @Nullable Path aside;
    private @Nullable FileSnapshot committed;

    AtomicChange(String role, PathRoute route, FileSnapshot before, FileContent desired) {
        this.role = role;
        this.route = route;
        this.before = before;
        this.desired = desired;
    }

    void stage() throws IOException {
        if (!desired.exists()) {
            return;
        }
        Files.createDirectories(route.target().getParent());
        var source = desired.linkSource();
        if (source.isPresent()) {
            source.orElseThrow().verify();
            return;
        }
        stage = Files.createTempFile(route.target().getParent(), ".mcp-swap-new-", "");
        try {
            Files.write(stage, desired.bytes(), StandardOpenOption.TRUNCATE_EXISTING);
            Files.setPosixFilePermissions(stage, PosixFilePermissions.fromString(desired.permissions()));
            try (var channel = FileChannel.open(stage, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            staged = FileSnapshot.capture(stage);
        } catch (IOException error) {
            Files.deleteIfExists(stage);
            throw error;
        }
    }

    void commit(TransactionGuard guard, SwapHook hook) throws IOException {
        try {
            if (before.exists()) {
                hook.before(role + "-take-aside", route.logical());
                guard.verifyExcept(route.target());
                route.verify();
                var protectedBefore = guard.snapshot(route.logical());
                protectedBefore.verify();
                if (!before.same(protectedBefore)) {
                    if (!role.equals("config")
                            || !before.sameExceptLinks(protectedBefore)
                            || protectedBefore.links() != before.links() + 1) {
                        throw new IOException("file changed: " + route.logical());
                    }
                    before = protectedBefore;
                }
                aside = unique(route.target(), "old");
                move(route.target(), aside);
                if (!FileSnapshot.capture(aside).sameFile(before)
                        || FileSnapshot.capture(route.target()).exists()) {
                    throw new IOException("take-aside changed identity: " + route.logical());
                }
            }
            hook.before(role + "-publish", route.logical());
            guard.verifyExcept(route.target());
            verifyTransition();
            if (desired.exists()) {
                var source = desired.linkSource();
                if (source.isPresent()) {
                    source.orElseThrow().verify();
                    Files.createLink(route.target(), source.orElseThrow().path());
                } else {
                    if (stage == null || staged == null) {
                        throw new IOException("change was not staged: " + route.logical());
                    }
                    staged.verify();
                    Files.createLink(route.target(), stage);
                    Files.delete(stage);
                    stage = null;
                }
            }
            syncDirectory(route.physicalParent());
            committed = FileSnapshot.capture(route.target());
            verifyDesired(committed);
            guard.update(route.logical());
            if (desired.linkSource().isPresent()) {
                guard.updateTarget(desired.linkSource().orElseThrow().path());
            }
        } catch (IOException | RuntimeException error) {
            try {
                rollbackPartial(guard);
            } catch (IOException rollback) {
                error.addSuppressed(rollback);
            }
            throw error;
        }
    }

    void rollback(TransactionGuard guard) throws IOException {
        if (committed == null) {
            return;
        }
        guard.verifyLock();
        verifyRouteTransition();
        var current = FileSnapshot.capture(route.target());
        if (!current.same(committed)) {
            throw new IOException("cannot roll back a changed destination: " + route.logical());
        }
        Path discard = null;
        if (current.exists()) {
            discard = unique(route.target(), "rollback");
            move(route.target(), discard);
        }
        if (before.exists()) {
            if (aside == null || !FileSnapshot.capture(aside).sameFile(before)) {
                throw new IOException("rollback source changed: " + route.logical());
            }
            Files.createLink(route.target(), aside);
            Files.delete(aside);
            aside = null;
        }
        if (discard != null) {
            Files.delete(discard);
        }
        syncDirectory(route.physicalParent());
        committed = null;
        before.verify();
        guard.update(route.logical());
        if (desired.linkSource().isPresent()) {
            guard.updateTarget(desired.linkSource().orElseThrow().path());
        }
    }

    void cleanup() throws IOException {
        cleanupStage();
        if (aside != null) {
            if (!FileSnapshot.capture(aside).sameFile(before)) {
                throw new IOException("take-aside file changed: " + aside);
            }
            Files.delete(aside);
            aside = null;
        }
    }

    void cleanupStage() throws IOException {
        if (stage != null) {
            if (staged == null || !FileSnapshot.capture(stage).same(staged)) {
                throw new IOException("staged file changed: " + stage);
            }
            Files.delete(stage);
            stage = null;
        }
    }

    private void rollbackPartial(TransactionGuard guard) throws IOException {
        if (committed != null) {
            rollback(guard);
            return;
        }
        if (aside == null) {
            return;
        }
        guard.verifyLock();
        verifyRouteTransition();
        var current = FileSnapshot.capture(route.target());
        if (current.exists()) {
            throw new IOException("late destination preserved; original retained at " + aside);
        }
        if (!FileSnapshot.capture(aside).sameFile(before)) {
            throw new IOException("take-aside source changed: " + aside);
        }
        Files.createLink(route.target(), aside);
        Files.delete(aside);
        aside = null;
        before.verify();
        guard.update(route.logical());
    }

    private void verifyTransition() throws IOException {
        verifyRouteTransition();
        var current = FileSnapshot.capture(route.target());
        if (before.exists()) {
            if (aside == null
                    || current.exists()
                    || !FileSnapshot.capture(aside).sameFile(before)) {
                throw new IOException("replacement route changed: " + route.logical());
            }
        } else if (!current.same(before)) {
            throw new IOException("destination appeared: " + route.logical());
        }
    }

    private void verifyRouteTransition() throws IOException {
        if (route.symbolicLink()) {
            var attributes =
                    Files.readAttributes(route.logical(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isSymbolicLink()
                    || !Files.readSymbolicLink(route.logical()).toString().equals(route.linkTarget())
                    || !String.valueOf(attributes.fileKey()).equals(route.linkIdentity())) {
                throw new IOException("path route changed: " + route.logical());
            }
            return;
        }
        var current = PathRoute.inspect(route.logical());
        if (!current.target().equals(route.target())
                || !current.physicalParent().equals(route.physicalParent())
                || current.symbolicLink() != route.symbolicLink()
                || !current.linkTarget().equals(route.linkTarget())
                || !current.linkIdentity().equals(route.linkIdentity())) {
            throw new IOException("path route changed: " + route.logical());
        }
    }

    private void verifyDesired(FileSnapshot current) throws IOException {
        if (current.exists() != desired.exists()
                || (desired.exists()
                        && (!current.digest().equals(desired.digest())
                                || !current.permissions().equals(desired.permissions())))) {
            throw new IOException("published file does not match staged bytes: " + route.logical());
        }
        if (desired.linkSource().isPresent()
                && !current.identity().equals(desired.linkSource().orElseThrow().identity())) {
            throw new IOException("published hard link changed identity: " + route.logical());
        }
    }

    private static Path unique(Path target, String role) {
        return target.resolveSibling("." + target.getFileName() + ".mcp-swap-" + role + "-" + UUID.randomUUID());
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("filesystem does not support atomic config replacement", unsupported);
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
