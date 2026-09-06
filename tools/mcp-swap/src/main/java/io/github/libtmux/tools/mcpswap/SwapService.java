package io.github.libtmux.tools.mcpswap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        use(selected, allClients, serverName, server, dryRun, null);
    }

    List<PlannedSpec> previewUse(List<Client> selected, List<Client> allClients, String serverName, ServerSpec server)
            throws IOException {
        validateSelection(selected, allClients);
        TransactionGuard.preflight(allClients, SwapPaths.lock(home, environment));
        return planUse(selected, allClients, serverName, server).preflights();
    }

    void use(
            List<Client> selected,
            List<Client> allClients,
            String serverName,
            ServerSpec server,
            boolean dryRun,
            @Nullable List<PlannedSpec> preflighted)
            throws IOException {
        validateSelection(selected, allClients);
        if (dryRun) {
            previewUse(selected, allClients, serverName, server);
            return;
        }
        try (var lock = SwapLock.acquire(home, environment)) {
            var guard = TransactionGuard.capture(allClients, lock);
            var planning = planUse(selected, allClients, serverName, server);
            if (preflighted != null && !preflighted.equals(planning.preflights())) {
                throw new IOException("selected configuration changed after preflight; retry the swap");
            }
            var plans = planning.plans();
            Map<Client, AtomicChange> backupChanges = new LinkedHashMap<>();
            List<AtomicChange> configChanges = new ArrayList<>();
            for (var plan : plans) {
                if (!plan.active() && plan.config().exists()) {
                    backupChanges.put(
                            plan.client(),
                            new AtomicChange(
                                    "backup",
                                    plan.backupRoute(),
                                    plan.backup(),
                                    FileContent.of(
                                            plan.config().bytes(), plan.config().permissions())));
                }
                configChanges.add(new AtomicChange("config", plan.configRoute(), plan.config(), plan.desired()));
            }
            try {
                for (var change : backupChanges.values()) {
                    change.stage();
                }
                for (var change : configChanges) {
                    change.stage();
                }
                executeUse(plans, backupChanges, configChanges, guard);
            } catch (IOException | RuntimeException failure) {
                for (var change : backupChanges.values().stream().toList().reversed()) {
                    try {
                        change.cleanupStage(guard, hook);
                    } catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                for (var change : configChanges.reversed()) {
                    try {
                        change.cleanupStage(guard, hook);
                    } catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        }
    }

    private void executeUse(
            List<UsePlan> plans,
            Map<Client, AtomicChange> backupChanges,
            List<AtomicChange> configChanges,
            TransactionGuard guard)
            throws IOException {
        List<AtomicChange> changes = new ArrayList<>(backupChanges.values());
        for (var index = 0; index < plans.size(); index++) {
            var plan = plans.get(index);
            var backupChange = backupChanges.get(plan.client());
            if (plan.previousTop() != null) {
                if (backupChange == null) {
                    throw new IOException(
                            "new recovery layer has no backup: " + plan.client().label());
                }
                var previous = plan.previousTop();
                changes.add(new AtomicChange(
                        "state",
                        previous.stateRoute(),
                        previous.state(),
                        FileContent.of(
                                previous.record()
                                        .withCurrentIdentity(backupChange.stagedIdentity())
                                        .encode(),
                                PRIVATE_MODE)));
            }
            var record = plan.record();
            if (backupChange != null) {
                record = record.withBackupIdentity(backupChange.stagedIdentity());
            }
            changes.add(new AtomicChange(
                    "state",
                    plan.stateRoute(),
                    plan.state(),
                    FileContent.of(
                            record.withCurrentIdentity(configChanges.get(index).stagedIdentity())
                                    .encode(),
                            PRIVATE_MODE)));
        }
        changes.addAll(configChanges);
        execute(changes, guard);
    }

    void revert(List<Client> selected, List<Client> allClients, String serverName, boolean dryRun) throws IOException {
        validateSelection(selected, allClients);
        if (dryRun) {
            TransactionGuard.preflight(allClients, SwapPaths.lock(home, environment));
            planRevert(selected, allClients, serverName);
            return;
        }
        try (var lock = SwapLock.acquire(home, environment)) {
            var guard = TransactionGuard.capture(allClients, lock);
            var plans = planRevert(selected, allClients, serverName);
            List<AtomicChange> configChanges = new ArrayList<>();
            try {
                for (var plan : plans) {
                    var change = new AtomicChange(
                            "config",
                            plan.configRoute(),
                            plan.config(),
                            plan.layers()
                                    .getLast()
                                    .record()
                                    .original(plan.layers().getLast().backup()));
                    change.stage();
                    configChanges.add(change);
                }
                List<AtomicChange> changes = new ArrayList<>(configChanges);
                for (var index = 0; index < plans.size(); index++) {
                    var plan = plans.get(index);
                    if (plan.remaining() != null) {
                        var remaining = plan.remaining();
                        changes.add(new AtomicChange(
                                "state",
                                remaining.stateRoute(),
                                remaining.state(),
                                FileContent.of(
                                        remaining
                                                .record()
                                                .withCurrentIdentity(
                                                        configChanges.get(index).stagedIdentity())
                                                .encode(),
                                        PRIVATE_MODE)));
                    }
                    for (var layer : plan.layers()) {
                        changes.add(new AtomicChange("state", layer.stateRoute(), layer.state(), FileContent.absent()));
                    }
                    for (var layer : plan.layers()) {
                        if (layer.backup().exists()) {
                            changes.add(new AtomicChange(
                                    "backup", layer.backupRoute(), layer.backup(), FileContent.absent()));
                        }
                    }
                }
                execute(changes, guard);
            } catch (IOException | RuntimeException failure) {
                for (var change : configChanges.reversed()) {
                    try {
                        change.cleanupStage(guard, hook);
                    } catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        }
    }

    private UsePlanning planUse(List<Client> selected, List<Client> allClients, String serverName, ServerSpec server)
            throws IOException {
        List<UsePlan> plans = new ArrayList<>();
        List<PlannedSpec> preflights = new ArrayList<>();
        var recovery = loadRecovery(allClients, serverName);
        var sequence = recovery.nextSequence();
        for (var client : selected) {
            var group = recovery.groups().get(configKey(client));
            var active = recovery.layers().get(client);
            if (active != null && (group == null || !group.layers().getFirst().equals(active))) {
                throw new IOException(client.label() + " is not the active recovery layer");
            }
            var configRoute = group == null ? PathRoute.inspect(client.configPath()) : group.configRoute();
            var config = group == null ? FileSnapshot.capture(configRoute.target()) : group.config();
            if (config.exists() && config.links() != 1) {
                throw new IOException("config must not be hard linked for " + client.name());
            }
            var backupRoute = active == null ? PathRoute.inspect(SwapPaths.backup(client)) : active.backupRoute();
            var stateRoute = active == null ? PathRoute.inspect(SwapPaths.state(client)) : active.stateRoute();
            var backup = active == null ? FileSnapshot.capture(backupRoute.target()) : active.backup();
            var state = active == null ? FileSnapshot.capture(stateRoute.target()) : active.state();
            var raw = config.exists() ? config.bytes() : new byte[0];
            Optional<ServerSpec> current;
            final byte[] rendered;
            final ServerSpec effective;
            try {
                current = ConfigCodec.read(client, raw, serverName);
                effective = server.withEnvironment(
                        current.map(ServerSpec::environment).orElse(Map.of()));
                rendered = ConfigCodec.updateExact(client, raw, serverName, effective);
            } catch (IllegalArgumentException error) {
                throw new IOException(client.name() + " config cannot be updated", error);
            }
            preflights.add(new PlannedSpec(client.label(), effective));
            if (current.isPresent() && current.orElseThrow().equals(effective)) {
                continue;
            }
            var desired = FileContent.of(rendered, config.exists() ? config.permissions() : PRIVATE_MODE);
            final RecoveryRecord record;
            if (active == null) {
                if (sequence == Long.MAX_VALUE) {
                    throw new IOException("recovery state sequence is exhausted");
                }
                record = RecoveryRecord.create(client, serverName, configRoute, config, desired, effective, sequence++);
            } else {
                record = active.record().withCurrent(desired, effective);
            }
            plans.add(new UsePlan(
                    client,
                    active != null,
                    active == null && group != null ? group.layers().getFirst() : null,
                    configRoute,
                    config,
                    backupRoute,
                    backup,
                    stateRoute,
                    state,
                    desired,
                    record));
        }
        return new UsePlanning(List.copyOf(plans), List.copyOf(preflights));
    }

    private List<RevertPlan> planRevert(List<Client> selected, List<Client> allClients, String serverName)
            throws IOException {
        var recovery = loadRecovery(allClients, serverName);
        var selectedSet = new HashSet<>(selected);
        List<RevertPlan> plans = new ArrayList<>();
        for (var group : recovery.groups().values()) {
            var chosen = group.layers().stream()
                    .filter(layer -> selectedSet.contains(layer.client()))
                    .toList();
            if (chosen.isEmpty()) {
                continue;
            }
            if (!chosen.equals(group.layers().subList(0, chosen.size()))) {
                throw new IOException(chosen.getFirst().client().label() + " has a newer recovery layer");
            }
            var remaining = chosen.size() == group.layers().size()
                    ? null
                    : group.layers().get(chosen.size());
            plans.add(new RevertPlan(group.configRoute(), group.config(), List.copyOf(chosen), remaining));
        }
        plans.sort(java.util.Comparator.comparingLong(
                        (RevertPlan plan) -> plan.layers().getFirst().record().sequence())
                .reversed());
        return List.copyOf(plans);
    }

    private static RecoveryWorld loadRecovery(List<Client> allClients, String serverName) throws IOException {
        Map<Path, List<RecoveryLayer>> grouped = new LinkedHashMap<>();
        Map<Client, RecoveryLayer> layers = new LinkedHashMap<>();
        var sequences = new HashSet<Long>();
        long nextSequence = 0;
        for (var client : allClients) {
            var backupRoute = PathRoute.inspect(SwapPaths.backup(client));
            var stateRoute = PathRoute.inspect(SwapPaths.state(client));
            var backup = FileSnapshot.capture(backupRoute.target());
            var state = FileSnapshot.capture(stateRoute.target());
            if (!state.exists()) {
                if (backup.exists()) {
                    throw new IOException("recovery backup has no state for " + client.label());
                }
                continue;
            }
            if (state.links() != 1 || !state.permissions().equals(PRIVATE_MODE)) {
                throw new IOException("recovery state is not a private file for " + client.label());
            }
            var configRoute = PathRoute.inspect(client.configPath());
            var record = RecoveryRecord.decode(state.bytes());
            record.verifyStored(client, serverName, configRoute, backup);
            if (!sequences.add(record.sequence())) {
                throw new IOException("recovery state has duplicate sequence numbers");
            }
            nextSequence = record.sequence() == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : Math.max(nextSequence, record.sequence() + 1);
            var layer = new RecoveryLayer(client, backupRoute, backup, stateRoute, state, record);
            layers.put(client, layer);
            grouped.computeIfAbsent(configKey(client), ignored -> new ArrayList<>())
                    .add(layer);
        }
        Map<Path, RecoveryGroup> groups = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            var ordered = entry.getValue();
            ordered.sort(java.util.Comparator.comparingLong(
                            (RecoveryLayer layer) -> layer.record().sequence())
                    .reversed());
            var configRoute = PathRoute.inspect(ordered.getFirst().client().configPath());
            var config = FileSnapshot.capture(configRoute.target());
            ordered.getFirst().record().verifyCurrent(config);
            for (var index = 1; index < ordered.size(); index++) {
                ordered.get(index).record().verifyCurrent(ordered.get(index - 1).backup());
            }
            groups.put(entry.getKey(), new RecoveryGroup(configRoute, config, List.copyOf(ordered)));
        }
        return new RecoveryWorld(Map.copyOf(groups), Map.copyOf(layers), nextSequence);
    }

    private static Path configKey(Client client) {
        return client.configPath().toAbsolutePath().normalize();
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
                    change.rollback(guard, hook);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                    blocked = true;
                }
            }
            for (var change : changes.reversed()) {
                try {
                    if (blocked) {
                        change.cleanupStage(guard, hook);
                    } else {
                        change.cleanup(guard, hook);
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
                change.cleanup(guard, hook);
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
            @Nullable RecoveryLayer previousTop,
            PathRoute configRoute,
            FileSnapshot config,
            PathRoute backupRoute,
            FileSnapshot backup,
            PathRoute stateRoute,
            FileSnapshot state,
            FileContent desired,
            RecoveryRecord record) {}

    record PlannedSpec(String label, ServerSpec spec) {}

    private record UsePlanning(List<UsePlan> plans, List<PlannedSpec> preflights) {}

    private record RevertPlan(
            PathRoute configRoute,
            FileSnapshot config,
            List<RecoveryLayer> layers,
            @Nullable RecoveryLayer remaining) {}

    private record RecoveryLayer(
            Client client,
            PathRoute backupRoute,
            FileSnapshot backup,
            PathRoute stateRoute,
            FileSnapshot state,
            RecoveryRecord record) {}

    private record RecoveryGroup(PathRoute configRoute, FileSnapshot config, List<RecoveryLayer> layers) {}

    private record RecoveryWorld(
            Map<Path, RecoveryGroup> groups, Map<Client, RecoveryLayer> layers, long nextSequence) {}
}
