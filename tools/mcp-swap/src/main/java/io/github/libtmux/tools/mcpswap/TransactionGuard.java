package io.github.libtmux.tools.mcpswap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TransactionGuard {
    private final SwapLock lock;
    private final List<ProtectedPath> paths;

    private TransactionGuard(SwapLock lock, List<ProtectedPath> paths) {
        this.lock = lock;
        this.paths = paths;
    }

    static TransactionGuard capture(List<Client> clients, SwapLock lock) throws IOException {
        var paths = inspect(clients);
        rejectAliases(paths, lock.path(), lock.identity());
        return new TransactionGuard(lock, paths);
    }

    static void preflight(List<Client> clients, Path lockPath) throws IOException {
        SwapLock.preflight(lockPath);
        var lockSnapshot = FileSnapshot.capture(PathRoute.inspect(lockPath).target());
        rejectAliases(inspect(clients), lockPath, lockSnapshot.identity());
    }

    private static List<ProtectedPath> inspect(List<Client> clients) throws IOException {
        List<ProtectedPath> paths = new ArrayList<>();
        var configs = new java.util.HashSet<Path>();
        for (var client : clients) {
            var config = client.configPath().toAbsolutePath().normalize();
            if (configs.add(config)) {
                paths.add(capture(client.name() + " config", config, true, true));
            }
            paths.add(capture(client.label() + " backup", SwapPaths.backup(client), false, true));
            paths.add(capture(client.label() + " state", SwapPaths.state(client), false, true));
        }
        return paths;
    }

    void verifyExcept(Path changingTarget) throws IOException {
        lock.verify();
        for (var path : paths) {
            if (path.route().target().equals(changingTarget)) {
                continue;
            }
            path.route().verify();
            path.snapshot().verify();
        }
    }

    void verifyLock() throws IOException {
        lock.verify();
    }

    void update(Path logical, FileSnapshot expected) throws IOException {
        for (int index = 0; index < paths.size(); index++) {
            var path = paths.get(index);
            if (!path.route().logical().equals(logical.toAbsolutePath().normalize())) {
                continue;
            }
            var updated = capture(path.label(), logical, path.config(), false);
            if (!path.route().sameTopology(updated.route()) || !expected.same(updated.snapshot())) {
                throw new IOException("transaction path changed before update: " + logical);
            }
            paths.set(index, updated);
            return;
        }
        throw new IOException("transaction path is not protected: " + logical);
    }

    FileSnapshot snapshot(Path logical) throws IOException {
        var normalized = logical.toAbsolutePath().normalize();
        for (var path : paths) {
            if (path.route().logical().equals(normalized)) {
                return path.snapshot();
            }
        }
        throw new IOException("transaction path is not protected: " + logical);
    }

    private static ProtectedPath capture(String label, Path logical, boolean config, boolean requireSingleLink)
            throws IOException {
        var route = PathRoute.inspect(logical);
        if (!config && route.symbolicLink()) {
            throw new IOException(label + " must not be a symbolic link");
        }
        var snapshot = FileSnapshot.capture(route.target());
        if (!config && requireSingleLink && snapshot.exists() && snapshot.links() != 1) {
            throw new IOException(label + " must not be hard linked");
        }
        return new ProtectedPath(label, config, route, snapshot);
    }

    private static void rejectAliases(List<ProtectedPath> paths, Path lockPath, String lockIdentity)
            throws IOException {
        Map<Path, String> targets = new HashMap<>();
        Map<String, String> identities = new HashMap<>();
        for (var path : paths) {
            var previous = targets.putIfAbsent(path.route().target(), path.label());
            if (previous != null) {
                throw new IOException(path.label() + " aliases " + previous);
            }
            if (path.snapshot().exists()) {
                previous = identities.putIfAbsent(path.snapshot().identity(), path.label());
                if (previous != null) {
                    throw new IOException(path.label() + " is hard linked to " + previous);
                }
            }
        }
        var lockRoute = PathRoute.inspect(lockPath);
        var previous = targets.putIfAbsent(lockRoute.target(), "swap lock");
        if (previous != null) {
            throw new IOException(previous + " aliases the swap lock");
        }
        previous = identities.putIfAbsent(lockIdentity, "swap lock");
        if (previous != null) {
            throw new IOException(previous + " is hard linked to the swap lock");
        }
    }

    private record ProtectedPath(String label, boolean config, PathRoute route, FileSnapshot snapshot) {}
}
