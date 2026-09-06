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
    private @Nullable FileSnapshot published;
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
        if (staged != null) {
            staged.verify();
            return;
        }
        Files.createDirectories(route.target().getParent());
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

    String stagedIdentity() {
        if (staged == null) {
            throw new IllegalStateException("change was not staged: %s".formatted(route.logical()));
        }
        return staged.identity();
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
                if (stage == null || staged == null) {
                    throw new IOException("change was not staged: " + route.logical());
                }
                staged.verify();
                Files.createLink(route.target(), stage);
            }
            hook.before(role + "-post-publish", route.logical());
            syncDirectory(route.physicalParent());
            var publishedCandidate = FileSnapshot.capture(route.target());
            verifyDesired(publishedCandidate, desired.exists() && stage != null ? 2 : 1);
            published = publishedCandidate;
            if (stage != null && staged != null) {
                var linkedStage = FileSnapshot.capture(stage);
                if (!linkedStage.sameFile(published)) {
                    throw new IOException("published stage identity changed: " + route.logical());
                }
                removeExact(stage, linkedStage, guard, hook, role + "-stage-remove");
                stage = null;
            }
            syncDirectory(route.physicalParent());
            var candidate = FileSnapshot.capture(route.target());
            verifyDesired(candidate, desired.exists() ? 1 : 0);
            committed = candidate;
            hook.before(role + "-pre-update", route.logical());
            guard.update(route.logical(), committed);
        } catch (IOException | RuntimeException error) {
            try {
                rollbackPartial(guard, hook);
            } catch (IOException rollback) {
                error.addSuppressed(rollback);
            }
            throw error;
        }
    }

    void rollback(TransactionGuard guard, SwapHook hook) throws IOException {
        if (committed == null) {
            return;
        }
        guard.verifyLock();
        verifyRouteTransition();
        var current = FileSnapshot.capture(route.target());
        if (!current.same(committed)) {
            throw new IOException("cannot roll back a changed destination: " + route.logical());
        }
        if (current.exists()) {
            removeExact(route.target(), current, guard, hook, role + "-rollback-remove");
        }
        if (before.exists()) {
            restoreAside(guard, hook);
        }
        syncDirectory(route.physicalParent());
        committed = null;
        published = null;
        before.verify();
        guard.update(route.logical(), before);
    }

    void cleanup(TransactionGuard guard, SwapHook hook) throws IOException {
        cleanupStage(guard, hook);
        if (aside != null) {
            if (committed == null) {
                throw new IOException("original retained after incomplete change: " + aside);
            }
            verifyRouteTransition();
            if (!FileSnapshot.capture(route.target()).same(committed)) {
                throw new IOException("destination changed before cleanup: " + route.logical());
            }
            var retained = FileSnapshot.capture(aside);
            if (!retained.sameFile(before)) {
                throw new IOException("take-aside file changed: " + aside);
            }
            removeExact(aside, retained, guard, hook, role + "-cleanup-remove");
            aside = null;
        }
    }

    void cleanupStage(TransactionGuard guard, SwapHook hook) throws IOException {
        if (stage != null) {
            if (staged == null) {
                throw new IOException("staged file changed: " + stage);
            }
            var current = FileSnapshot.capture(stage);
            if (!current.same(staged)) {
                throw new IOException("staged file changed: " + stage);
            }
            removeExact(stage, current, guard, hook, role + "-stage-cleanup-remove");
            stage = null;
        }
    }

    private void rollbackPartial(TransactionGuard guard, SwapHook hook) throws IOException {
        if (committed != null) {
            rollback(guard, hook);
            return;
        }
        var current = FileSnapshot.capture(route.target());
        if (published != null && current.sameExceptLinks(published)) {
            committed = current;
            rollback(guard, hook);
            return;
        }
        if (aside == null) {
            return;
        }
        guard.verifyLock();
        verifyRouteTransition();
        if (current.exists()) {
            throw new IOException("late destination preserved; original retained at " + aside);
        }
        restoreAside(guard, hook);
        before.verify();
        guard.update(route.logical(), before);
    }

    private void restoreAside(TransactionGuard guard, SwapHook hook) throws IOException {
        if (aside == null) {
            throw new IOException("rollback source is missing: " + route.logical());
        }
        var retained = FileSnapshot.capture(aside);
        if (!retained.sameFile(before)) {
            throw new IOException("rollback source changed: " + route.logical());
        }
        Files.createLink(route.target(), aside);
        var restored = FileSnapshot.capture(route.target());
        var linkedAside = FileSnapshot.capture(aside);
        if (!restored.sameFile(linkedAside) || !restored.sameExceptLinks(before)) {
            throw new IOException("rollback publication changed: " + route.logical());
        }
        removeExact(aside, linkedAside, guard, hook, role + "-rollback-source-remove");
        aside = null;
        if (!FileSnapshot.capture(route.target()).same(before)) {
            throw new IOException("rollback destination changed: " + route.logical());
        }
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

    private void verifyDesired(FileSnapshot current, int links) throws IOException {
        if (current.exists() != desired.exists()
                || (desired.exists()
                        && (!current.digest().equals(desired.digest())
                                || !current.permissions().equals(desired.permissions())
                                || current.links() != links))) {
            throw new IOException("published file does not match staged bytes: " + route.logical());
        }
        if (desired.exists() && (staged == null || !current.sameExceptLinks(staged))) {
            throw new IOException("published hard link changed identity: " + route.logical());
        }
    }

    private static void removeExact(
            Path path, FileSnapshot expected, TransactionGuard guard, SwapHook hook, String boundary)
            throws IOException {
        guard.verifyLock();
        hook.before(boundary, path);
        guard.verifyLock();
        if (!FileSnapshot.capture(path).same(expected)) {
            throw new IOException("file changed before removal: " + path);
        }
        var parent = path.getParent();
        if (parent == null) {
            throw new IOException("file has no parent: " + path);
        }
        var retainedDirectory = Files.createTempDirectory(
                parent,
                "." + path.getFileName() + ".mcp-swap-retained-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var retained = retainedDirectory.resolve("artifact");
        move(path, retained);
        var moved = FileSnapshot.capture(retained);
        if (!moved.sameFile(expected)) {
            throw new IOException("file changed during removal; retained at " + retainedDirectory);
        }
        if (FileSnapshot.capture(path).exists()) {
            throw new IOException("late destination preserved; recovery retained at " + retainedDirectory);
        }
        moved.verify();
        Files.delete(retained);
        if (Files.exists(retained, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("removed file remains at " + retained);
        }
        Files.delete(retainedDirectory);
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
