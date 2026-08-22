package sh.zolt.cli.command.dependency;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.project.VersionPolicy;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Shared parsing, lookup, and description helpers for the manifest mutation commands. */
final class DependencyEditCommands {
    /** Lanes whose variants are mutually exclusive, so an add moves rather than duplicates (§9.7). */
    private static final Set<DependencyLane> ORDINARY_LANES = EnumSet.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME,
            DependencyLane.PROVIDED,
            DependencyLane.DEV,
            DependencyLane.TEST);

    private DependencyEditCommands() {
    }

    record AddRequest(DependencyLane lane, DependencyCoordinate coordinate, DependencySelector selector) {
    }

    /** The lane named by {@code --scope}; implementation is the default lane (design §20). */
    static DependencyLane parseScope(String value, String command) {
        if (value == null) {
            return DependencyLane.IMPLEMENTATION;
        }
        return switch (value) {
            case "implementation" -> DependencyLane.IMPLEMENTATION;
            case "api" -> DependencyLane.API;
            case "runtime" -> DependencyLane.RUNTIME;
            case "provided" -> DependencyLane.PROVIDED;
            case "dev" -> DependencyLane.DEV;
            case "test" -> DependencyLane.TEST;
            case "processor" -> DependencyLane.PROCESSOR;
            case "test-processor" -> DependencyLane.TEST_PROCESSOR;
            default -> throw new DependencyScopeException("Unexpected dependency scope `" + value
                    + "`. Use `" + command
                    + " group:artifact --scope <implementation|api|runtime|provided|dev|test|processor|test-processor>`.");
        };
    }

    /** The canonical table that owns {@code lane}, without brackets. */
    static String section(DependencyLane lane) {
        return switch (lane) {
            case IMPLEMENTATION -> "dependencies";
            case API -> "dependencies.api";
            case RUNTIME -> "dependencies.runtime";
            case PROVIDED -> "dependencies.provided";
            case DEV -> "dependencies.dev";
            case TEST -> "dependencies.test";
            case PROCESSOR -> "dependencies.processor";
            case TEST_PROCESSOR -> "dependencies.test-processor";
        };
    }

    static List<AuthoredDependency> declarations(AuthoredManifest manifest) {
        return manifest.dependencies().map(AuthoredDependencies::declarations).orElseGet(List::of);
    }

    static Optional<AuthoredDependency> find(
            AuthoredManifest manifest, DependencyLane lane, DependencyCoordinate coordinate) {
        return declarations(manifest).stream()
                .filter(dependency -> dependency.lane() == lane && dependency.coordinate().equals(coordinate))
                .findFirst();
    }

    /** The ordinary-lane declaration an add would move, if the variant lives in a different lane. */
    static Optional<AuthoredDependency> findMovable(
            AuthoredManifest manifest, DependencyLane lane, DependencyCoordinate coordinate) {
        if (!ORDINARY_LANES.contains(lane)) {
            return Optional.empty();
        }
        return declarations(manifest).stream()
                .filter(dependency -> dependency.lane() != lane
                        && ORDINARY_LANES.contains(dependency.lane())
                        && dependency.coordinate().equals(coordinate))
                .findFirst();
    }

    static Map<LocalId, VersionAliasValue> versionAliases(AuthoredManifest manifest) {
        return manifest.versions().map(AuthoredVersionAliases::entries).orElseGet(Map::of);
    }

    /**
     * The aliases one manifest may reference by {@code versionRef}.
     *
     * <p>Design §4.5 "Named maps": workspace versions are available to every member, and a
     * root-owned alias may not be redeclared in a member. A member therefore references aliases it
     * does not author, so validation reads the effective root-merged view rather than the member's
     * own {@code [versions]} table. A virtual workspace root has nothing above it to merge.
     */
    static VersionAliasView aliasView(
            ManifestMutationServices manifests,
            Path projectDirectory,
            AuthoredManifest manifest) {
        Optional<AuthoredManifest> root = manifests.workspaceRootManifest(projectDirectory);
        if (root.isEmpty()) {
            return new VersionAliasView(versionAliases(manifest), false);
        }
        Map<LocalId, VersionAliasValue> entries =
                new LinkedHashMap<>(versionAliases(root.orElseThrow()));
        entries.putAll(versionAliases(manifest));
        return new VersionAliasView(entries, true);
    }

    /** The alias names a manifest may reference, and whether a workspace root can also own them. */
    record VersionAliasView(Map<LocalId, VersionAliasValue> entries, boolean workspaceMember) {
        VersionAliasView {
            entries = Map.copyOf(entries);
        }

        String remedy(String alias) {
            return workspaceMember
                    ? "Declare [versions]." + alias
                            + " in this member or in the workspace root, or use an explicit version."
                    : "Add [versions]." + alias + " or use an explicit version.";
        }
    }

    /** The literal a version alias resolves to, rejecting an alias no visible manifest declares. */
    static String requireAlias(
            VersionAliasView aliases, String alias, Function<String, RuntimeException> failure) {
        VersionAliasValue value = aliases.entries().get(localId(alias, failure));
        if (value == null) {
            throw failure.apply(
                    "Unknown versionRef `" + alias + "`. " + aliases.remedy(alias));
        }
        return value.value();
    }

    static LocalId localId(String alias, Function<String, RuntimeException> failure) {
        try {
            return new LocalId(alias);
        } catch (IllegalArgumentException exception) {
            throw failure.apply("Invalid version alias `" + alias
                    + "`. Alias names use lowercase kebab-case.");
        }
    }

    static DependencyCoordinate coordinate(String value, Function<String, RuntimeException> failure) {
        try {
            return new DependencyCoordinate(value);
        } catch (IllegalArgumentException exception) {
            throw failure.apply(exception.getMessage());
        }
    }

    /** How one selector reads in command output, with an alias shown beside the value it resolves to. */
    static String describe(AuthoredManifest manifest, DependencySelector selector) {
        return switch (selector) {
            case DependencySelector.FixedVersion fixed -> fixed.value();
            case DependencySelector.VersionReference reference -> alias(manifest, reference.alias());
            case DependencySelector.Managed ignored -> "a platform-managed version";
            case DependencySelector.Workspace ignored -> "its workspace member";
        };
    }

    static String describe(AuthoredManifest manifest, PlatformSelector selector) {
        return switch (selector) {
            case PlatformSelector.FixedVersion fixed -> fixed.value();
            case PlatformSelector.VersionReference reference -> alias(manifest, reference.alias());
        };
    }

    private static String alias(AuthoredManifest manifest, LocalId id) {
        VersionAliasValue value = versionAliases(manifest).get(id);
        return value == null
                ? "versionRef `" + id + "`"
                : "versionRef `" + id + "` = " + value.value();
    }

    static <T extends RuntimeException> void validateCommandVersion(
            VersionPolicy.Context context,
            String subject,
            String version,
            Function<String, T> exceptionFactory) {
        validateCommandVersion(context, subject, version, false, exceptionFactory);
    }

    static <T extends RuntimeException> void validateCommandVersion(
            VersionPolicy.Context context,
            String subject,
            String version,
            boolean snapshotPermitted,
            Function<String, T> exceptionFactory) {
        VersionPolicy.violation(context, version, snapshotPermitted).ifPresent(violation -> {
            throw exceptionFactory.apply(
                    "Invalid " + context.description() + " `" + version + "` for " + subject + ". "
                            + violation.guidance());
        });
    }

    static final class AddCommandException extends RuntimeException {
        AddCommandException(String message) {
            super(message);
        }
    }

    static final class RemoveCommandException extends RuntimeException {
        RemoveCommandException(String message) {
            super(message);
        }
    }

    static final class DependencyScopeException extends RuntimeException {
        DependencyScopeException(String message) {
            super(message);
        }
    }

    static final class PlatformCommandException extends RuntimeException {
        PlatformCommandException(String message) {
            super(message);
        }
    }

    static final class BomCommandException extends RuntimeException {
        BomCommandException(String message) {
            super(message);
        }
    }
}
