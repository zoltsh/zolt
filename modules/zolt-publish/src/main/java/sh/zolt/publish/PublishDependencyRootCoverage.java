package sh.zolt.publish;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;

/** Fail-closed coverage validation between publishable declarations and lockfile-v7 roots. */
public final class PublishDependencyRootCoverage {
    private PublishDependencyRootCoverage() {
    }

    public static void requireComplete(ProjectConfig config, ZoltLockfile lockfile) {
        if (lockfile.version() != ZoltLockfile.CURRENT_VERSION) {
            throw new PublishException(
                    "Publication requires zolt.lock version " + ZoltLockfile.CURRENT_VERSION + ", but found version "
                            + lockfile.version() + ". Run `zolt resolve` to regenerate the lockfile.");
        }
        if (lockfile.dependencyRoots().stream().anyMatch(root -> !root.member().equals("."))) {
            throw new PublishException(
                    "Publication requires a standalone or member-projected lock whose dependency roots use member `.`. "
                            + "Run `zolt resolve` or `zolt resolve --workspace` to regenerate the lockfile.");
        }

        Map<Identity, Optional<String>> expected = expected(config);
        Map<Identity, String> actual = new LinkedHashMap<>();
        lockfile.dependencyRoots().stream()
                .filter(root -> published(root.lane()))
                .forEach(root -> actual.put(Identity.of(root), root.version()));
        Set<Identity> missing = new LinkedHashSet<>(expected.keySet());
        missing.removeAll(actual.keySet());
        Set<Identity> unexpected = new LinkedHashSet<>(actual.keySet());
        unexpected.removeAll(expected.keySet());
        Set<String> versionMismatches = new LinkedHashSet<>();
        expected.forEach((identity, version) -> version.ifPresent(expectedVersion -> {
            String actualVersion = actual.get(identity);
            if (actualVersion != null && !expectedVersion.equals(actualVersion)) {
                versionMismatches.add(identity.description() + " expected `" + expectedVersion
                        + "` but locked `" + actualVersion + "`");
            }
        }));
        if (missing.isEmpty() && unexpected.isEmpty() && versionMismatches.isEmpty()) {
            return;
        }
        throw new PublishException(
                "Publication dependency roots do not match the current manifest; missing " + describe(missing)
                        + ", unexpected " + describe(unexpected)
                        + ", version mismatches " + versionMismatches.stream().sorted().toList()
                        + ". Run `zolt resolve` to regenerate the lockfile.");
    }

    private static Map<Identity, Optional<String>> expected(ProjectConfig config) {
        Map<Identity, Optional<String>> expected = new LinkedHashMap<>();
        addUnversioned(expected, config, DependencyLane.API, config.apiDependencies().keySet());
        addUnversioned(expected, config, DependencyLane.API, config.managedApiDependencies());
        addUnversioned(expected, config, DependencyLane.API, config.workspaceApiDependencies().keySet());
        addUnversioned(expected, config, DependencyLane.IMPLEMENTATION, config.dependencies().keySet());
        addUnversioned(expected, config, DependencyLane.IMPLEMENTATION, config.managedDependencies());
        addUnversioned(expected, config, DependencyLane.IMPLEMENTATION, config.workspaceDependencies().keySet());
        addUnversioned(expected, config, DependencyLane.RUNTIME, config.runtimeDependencies().keySet());
        addUnversioned(expected, config, DependencyLane.RUNTIME, config.managedRuntimeDependencies());
        addUnversioned(expected, config, DependencyLane.PROVIDED, config.providedDependencies().keySet());
        addUnversioned(expected, config, DependencyLane.PROVIDED, config.managedProvidedDependencies());
        config.dependencyMetadata().values().stream()
                .filter(DependencyMetadata::publishOnly)
                .forEach(metadata -> expected.put(
                        identity(config, lane(metadata.section()), metadata.coordinate(), true),
                        Optional.ofNullable(metadata.version())));
        return Map.copyOf(expected);
    }

    private static void addUnversioned(
            Map<Identity, Optional<String>> expected,
            ProjectConfig config,
            DependencyLane lane,
            Iterable<String> coordinates) {
        for (String coordinate : coordinates) {
            expected.put(identity(config, lane, coordinate, false), Optional.empty());
        }
    }

    private static Identity identity(
            ProjectConfig config,
            DependencyLane lane,
            String coordinate,
            boolean publishOnly) {
        DependencyMetadata metadata =
                config.dependencyMetadata().get(PublishDependencyMetadataKey.of(lane, coordinate));
        LockArtifactVariant variant = metadata == null
                ? LockArtifactVariant.defaultVariant()
                : new LockArtifactVariant(
                        metadata.type() == null ? "jar" : metadata.type(),
                        Optional.ofNullable(metadata.classifier()));
        return new Identity(
                lane,
                packageId(coordinate),
                variant,
                metadata != null && metadata.optional(),
                publishOnly);
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new PublishException("Invalid dependency coordinate `" + coordinate + "` in publication metadata.");
        }
        return new PackageId(parts[0], parts[1]);
    }

    private static DependencyLane lane(String section) {
        return switch (section) {
            case "api.dependencies" -> DependencyLane.API;
            case "dependencies" -> DependencyLane.IMPLEMENTATION;
            case "runtime.dependencies" -> DependencyLane.RUNTIME;
            case "provided.dependencies" -> DependencyLane.PROVIDED;
            default -> throw new PublishException(
                    "Publish-only dependency metadata uses unsupported section `" + section + "`.");
        };
    }

    private static boolean published(DependencyLane lane) {
        return switch (lane) {
            case API, IMPLEMENTATION, RUNTIME, PROVIDED -> true;
            case DEV, TEST, PROCESSOR, TEST_PROCESSOR -> false;
        };
    }

    private static String describe(Set<Identity> identities) {
        return identities.stream().map(Identity::description).sorted().toList().toString();
    }

    private record Identity(
            DependencyLane lane,
            PackageId packageId,
            LockArtifactVariant variant,
            boolean optional,
            boolean publishOnly) {
        static Identity of(LockDependencyRoot root) {
            return new Identity(
                    root.lane(), root.packageId(), root.variant(), root.optional(), root.publishOnly());
        }

        String description() {
            String flags = (optional ? ":optional" : "") + (publishOnly ? ":publish-only" : "");
            return "`" + lane.name().toLowerCase().replace('_', '-') + ":" + packageId + ":" + variant.key()
                    + flags + "`";
        }
    }
}
