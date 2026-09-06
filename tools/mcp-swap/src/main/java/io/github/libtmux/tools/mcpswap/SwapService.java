package io.github.libtmux.tools.mcpswap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class SwapService {
    private static final String PRIVATE_MODE = "rw-------";

    private final Path home;
    private final Map<String, String> environment;
    private final SwapHook hook;

    SwapService(Path home, Map<String, String> environment) {
        this(home, environment, SwapHook.NONE);
    }

    SwapService(Path home, Map<String, String> environment, SwapHook hook) {
        this.home = home.toAbsolutePath().normalize();
        this.environment = Map.copyOf(environment);
        this.hook = hook;
    }

    void use(List<Client> selected, List<Client> allClients, String serverName, ServerSpec server, boolean dryRun)
            throws IOException {
        validateSelection(selected, allClients);
        if (dryRun) {
            TransactionGuard.preflight(allClients, SwapPaths.lock(home, environment));
            planUse(selected, serverName, server);
            return;
        }
        try (var lock = SwapLock.acquire(home, environment)) {
            var guard = TransactionGuard.capture(allClients, lock);
            var plans = planUse(selected, serverName, server);
            List<AtomicChange> changes = new ArrayList<>();
            for (var plan : plans) {
                if (!plan.active() && plan.config().exists()) {
                    changes.add(new AtomicChange(
                            "backup", plan.backupRoute(), plan.backup(), FileContent.linked(plan.config())));
                }
            }
            for (var plan : plans) {
                changes.add(new AtomicChange(
                        "state",
                        plan.stateRoute(),
                        plan.state(),
                        FileContent.of(plan.record().encode(), PRIVATE_MODE)));
            }
            for (var plan : plans) {
                changes.add(new AtomicChange("config", plan.configRoute(), plan.config(), plan.desired()));
            }
            execute(changes, guard);
        }
    }

    void revert(List<Client> selected, List<Client> allClients, String serverName, boolean dryRun) throws IOException {
        validateSelection(selected, allClients);
        if (dryRun) {
            TransactionGuard.preflight(allClients, SwapPaths.lock(home, environment));
            planRevert(selected, serverName);
            return;
        }
        try (var lock = SwapLock.acquire(home, environment)) {
            var guard = TransactionGuard.capture(allClients, lock);
            var plans = planRevert(selected, serverName);
            List<AtomicChange> changes = new ArrayList<>();
            for (var plan : plans) {
                changes.add(new AtomicChange(
                        "config",
                        plan.configRoute(),
                        plan.config(),
                        plan.record().original(plan.backup())));
            }
            for (var plan : plans) {
                changes.add(new AtomicChange("state", plan.stateRoute(), plan.state(), FileContent.absent()));
            }
            for (var plan : plans) {
                if (plan.backup().exists()) {
                    changes.add(new AtomicChange("backup", plan.backupRoute(), plan.backup(), FileContent.absent()));
                }
            }
            execute(changes, guard);
        }
    }

    private List<UsePlan> planUse(List<Client> selected, String serverName, ServerSpec server) throws IOException {
        List<UsePlan> plans = new ArrayList<>();
        for (var client : selected) {
            var configRoute = PathRoute.inspect(client.configPath());
            var backupRoute = PathRoute.inspect(SwapPaths.backup(client));
            var stateRoute = PathRoute.inspect(SwapPaths.state(client));
            var config = FileSnapshot.capture(configRoute.target());
            if (config.exists() && config.links() != 1) {
                throw new IOException("config must not be hard linked for " + client.name());
            }
            var backup = FileSnapshot.capture(backupRoute.target());
            var state = FileSnapshot.capture(stateRoute.target());
            var active = recovery(client, serverName, configRoute, config, backup, state);
            var raw = config.exists() ? config.bytes() : new byte[0];
            final byte[] rendered;
            try {
                rendered = ConfigCodec.update(client, raw, serverName, server);
            } catch (IllegalArgumentException error) {
                throw new IOException(client.name() + " config cannot be updated", error);
            }
            var desired = FileContent.of(rendered, config.exists() ? config.permissions() : PRIVATE_MODE);
            var record = active == null
                    ? RecoveryRecord.create(client, serverName, configRoute, config, desired, server)
                    : active.withCurrent(desired, server);
            plans.add(new UsePlan(
                    client,
                    active != null,
                    configRoute,
                    config,
                    backupRoute,
                    backup,
                    stateRoute,
                    state,
                    desired,
                    record));
        }
        return List.copyOf(plans);
    }

    private List<RevertPlan> planRevert(List<Client> selected, String serverName) throws IOException {
        List<RevertPlan> plans = new ArrayList<>();
        for (var client : selected) {
            var configRoute = PathRoute.inspect(client.configPath());
            var backupRoute = PathRoute.inspect(SwapPaths.backup(client));
            var stateRoute = PathRoute.inspect(SwapPaths.state(client));
            var config = FileSnapshot.capture(configRoute.target());
            var backup = FileSnapshot.capture(backupRoute.target());
            var state = FileSnapshot.capture(stateRoute.target());
            if (!backup.exists() && !state.exists()) {
                continue;
            }
            var record = recovery(client, serverName, configRoute, config, backup, state);
            if (record == null) {
                throw new IOException("recovery pair is incomplete for " + client.name());
            }
            plans.add(new RevertPlan(client, configRoute, config, backupRoute, backup, stateRoute, state, record));
        }
        return List.copyOf(plans);
    }

    private static @Nullable RecoveryRecord recovery(
            Client client,
            String serverName,
            PathRoute configRoute,
            FileSnapshot config,
            FileSnapshot backup,
            FileSnapshot state)
            throws IOException {
        if (!state.exists()) {
            if (backup.exists()) {
                throw new IOException("recovery backup has no state for " + client.name());
            }
            return null;
        }
        if (state.links() != 1 || !state.permissions().equals(PRIVATE_MODE)) {
            throw new IOException("recovery state is not a private file for " + client.name());
        }
        var record = RecoveryRecord.decode(state.bytes());
        record.verify(client, serverName, configRoute, config, backup);
        return record;
    }

    private void execute(List<AtomicChange> changes, TransactionGuard guard) throws IOException {
        List<AtomicChange> committed = new ArrayList<>();
        try {
            for (var change : changes) {
                change.stage();
            }
            for (var change : changes) {
                change.commit(guard, hook);
                committed.add(change);
            }
        } catch (IOException | RuntimeException failure) {
            var blocked = failure.getSuppressed().length != 0;
            for (var change : committed.reversed()) {
                if (blocked) {
                    break;
                }
                try {
                    change.rollback(guard);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                    blocked = true;
                }
            }
            for (var change : changes.reversed()) {
                try {
                    if (blocked) {
                        change.cleanupStage();
                    } else {
                        change.cleanup();
                    }
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
        IOException cleanupFailure = null;
        for (var change : changes.reversed()) {
            try {
                change.cleanup();
            } catch (IOException error) {
                if (cleanupFailure == null) {
                    cleanupFailure = error;
                } else {
                    cleanupFailure.addSuppressed(error);
                }
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private static void validateSelection(List<Client> selected, List<Client> allClients) {
        var known = new HashSet<>(allClients);
        var distinct = new HashSet<Client>();
        for (var client : selected) {
            if (!known.contains(client) || !distinct.add(client)) {
                throw new IllegalArgumentException("client selection is not a distinct known subset");
            }
        }
    }

    private record UsePlan(
            Client client,
            boolean active,
            PathRoute configRoute,
            FileSnapshot config,
            PathRoute backupRoute,
            FileSnapshot backup,
            PathRoute stateRoute,
            FileSnapshot state,
            FileContent desired,
            RecoveryRecord record) {}

    private record RevertPlan(
            Client client,
            PathRoute configRoute,
            FileSnapshot config,
            PathRoute backupRoute,
            FileSnapshot backup,
            PathRoute stateRoute,
            FileSnapshot state,
            RecoveryRecord record) {}
}
