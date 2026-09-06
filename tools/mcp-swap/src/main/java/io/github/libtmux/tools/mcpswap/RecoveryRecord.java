package io.github.libtmux.tools.mcpswap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

record RecoveryRecord(
        int version,
        String client,
        String server,
        String logical,
        String target,
        String physicalParent,
        boolean symbolicLink,
        String linkTarget,
        boolean originalExists,
        String backupIdentity,
        String originalDigest,
        String originalPermissions,
        String currentDigest,
        String currentPermissions,
        String command,
        List<String> arguments) {
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 16 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of(
            "version",
            "client",
            "server",
            "logical",
            "target",
            "physicalParent",
            "symbolicLink",
            "linkTarget",
            "originalExists",
            "backupIdentity",
            "originalDigest",
            "originalPermissions",
            "currentDigest",
            "currentPermissions",
            "command",
            "arguments",
            "checksum");

    RecoveryRecord {
        arguments = List.copyOf(arguments);
    }

    static RecoveryRecord create(
            Client client,
            String server,
            PathRoute route,
            FileSnapshot original,
            FileContent current,
            ServerSpec spec) {
        return new RecoveryRecord(
                VERSION,
                client.name(),
                server,
                route.logical().toString(),
                route.target().toString(),
                route.physicalParent().toString(),
                route.symbolicLink(),
                route.linkTarget(),
                original.exists(),
                original.identity(),
                original.digest(),
                original.permissions(),
                current.digest(),
                current.permissions(),
                spec.command(),
                spec.arguments());
    }

    RecoveryRecord withCurrent(FileContent current, ServerSpec spec) {
        return new RecoveryRecord(
                version,
                client,
                server,
                logical,
                target,
                physicalParent,
                symbolicLink,
                linkTarget,
                originalExists,
                backupIdentity,
                originalDigest,
                originalPermissions,
                current.digest(),
                current.permissions(),
                spec.command(),
                spec.arguments());
    }

    byte[] encode() {
        try {
            var body = body();
            var checksum = FileSnapshot.sha256(JSON.writeValueAsBytes(body));
            body.put("checksum", checksum);
            var encoded = JSON.writeValueAsBytes(body);
            if (encoded.length > MAX_BYTES) {
                throw new IllegalArgumentException("recovery state exceeds 16 KiB");
            }
            return encoded;
        } catch (JsonProcessingException impossible) {
            throw new IllegalArgumentException("cannot encode recovery state", impossible);
        }
    }

    static RecoveryRecord decode(byte[] encoded) throws IOException {
        if (encoded.length > MAX_BYTES) {
            throw new IOException("recovery state exceeds 16 KiB");
        }
        final ObjectNode root;
        try {
            var parsed = JSON.readTree(encoded);
            if (!(parsed instanceof ObjectNode object)) {
                throw new IOException("recovery state is not an object");
            }
            root = object;
        } catch (JsonProcessingException error) {
            throw new IOException("recovery state is not valid JSON", error);
        }
        var fields = root.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        if (!fields.equals(FIELDS)) {
            throw new IOException("recovery state fields are not canonical");
        }
        var checksum = requiredText(root, "checksum");
        root.remove("checksum");
        try {
            if (!checksum.equals(FileSnapshot.sha256(JSON.writeValueAsBytes(root)))) {
                throw new IOException("recovery state checksum changed");
            }
        } catch (JsonProcessingException impossible) {
            throw new IOException("cannot verify recovery state", impossible);
        }
        var version = root.path("version").asInt(-1);
        if (version != VERSION) {
            throw new IOException("unsupported recovery state version " + version);
        }
        var rawArguments = root.path("arguments");
        if (!rawArguments.isArray()) {
            throw new IOException("recovery arguments are not an array");
        }
        List<String> arguments = new ArrayList<>();
        for (var value : rawArguments) {
            if (!value.isTextual()) {
                throw new IOException("recovery arguments must contain strings");
            }
            arguments.add(value.textValue());
        }
        return new RecoveryRecord(
                version,
                requiredText(root, "client"),
                requiredText(root, "server"),
                requiredText(root, "logical"),
                requiredText(root, "target"),
                requiredText(root, "physicalParent"),
                root.path("symbolicLink").asBoolean(),
                requiredText(root, "linkTarget"),
                root.path("originalExists").asBoolean(),
                requiredText(root, "backupIdentity"),
                requiredText(root, "originalDigest"),
                requiredText(root, "originalPermissions"),
                requiredText(root, "currentDigest"),
                requiredText(root, "currentPermissions"),
                requiredText(root, "command"),
                arguments);
    }

    void verify(
            Client expectedClient, String expectedServer, PathRoute route, FileSnapshot current, FileSnapshot backup)
            throws IOException {
        if (!client.equals(expectedClient.name()) || !server.equals(expectedServer)) {
            throw new IOException("recovery state belongs to another client or server");
        }
        if (!logical.equals(route.logical().toString())
                || !target.equals(route.target().toString())
                || !physicalParent.equals(route.physicalParent().toString())
                || symbolicLink != route.symbolicLink()
                || !linkTarget.equals(route.linkTarget())) {
            throw new IOException("recovery config route changed for " + client);
        }
        if (!current.exists()
                || !current.digest().equals(currentDigest)
                || !current.permissions().equals(currentPermissions)) {
            throw new IOException("swapped config changed for " + client);
        }
        if (originalExists) {
            if (!backup.exists()
                    || backup.links() != 1
                    || !backup.identity().equals(backupIdentity)
                    || !backup.digest().equals(originalDigest)
                    || !backup.permissions().equals(originalPermissions)) {
                throw new IOException("recovery backup changed for " + client);
            }
        } else if (backup.exists()) {
            throw new IOException("unexpected recovery backup for " + client);
        }
    }

    FileContent original(FileSnapshot backup) {
        return originalExists ? FileContent.of(backup.bytes(), originalPermissions) : FileContent.absent();
    }

    private ObjectNode body() {
        var root = JSON.createObjectNode();
        root.put("version", version);
        root.put("client", client);
        root.put("server", server);
        root.put("logical", logical);
        root.put("target", target);
        root.put("physicalParent", physicalParent);
        root.put("symbolicLink", symbolicLink);
        root.put("linkTarget", linkTarget);
        root.put("originalExists", originalExists);
        root.put("backupIdentity", backupIdentity);
        root.put("originalDigest", originalDigest);
        root.put("originalPermissions", originalPermissions);
        root.put("currentDigest", currentDigest);
        root.put("currentPermissions", currentPermissions);
        root.put("command", command);
        var values = root.putArray("arguments");
        arguments.forEach(values::add);
        return root;
    }

    private static String requiredText(ObjectNode root, String name) throws IOException {
        var value = root.get(name);
        if (value == null || !value.isTextual()) {
            throw new IOException("recovery field " + name + " is not a string");
        }
        return value.textValue();
    }
}
