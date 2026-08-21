package sh.zolt.lockfile.toml;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.DependencyLane;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlInvalidTypeException;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public final class ZoltLockfileReader {
    private static final int MIN_SUPPORTED_VERSION = 7;
    private final LockfilePackageCodec packageCodec;

    public ZoltLockfileReader() {
        this(new LockfilePackageCodec());
    }

    ZoltLockfileReader(LockfilePackageCodec packageCodec) {
        this.packageCodec = packageCodec;
    }

    public ZoltLockfile read(Path path) {
        try {
            return read(Toml.parse(path));
        } catch (IOException exception) {
            throw LockfileReadException.actionable(
                    "Could not read zolt.lock at " + path + ".",
                    "Check that the file exists and is readable.",
                    exception);
        }
    }

    public ZoltLockfile read(String content) {
        return read(Toml.parse(content));
    }

    private ZoltLockfile read(TomlParseResult result) {
        try {
            if (result.hasErrors()) {
                TomlParseError error = result.errors().getFirst();
                throw LockfileReadException.actionable(
                        "Could not parse zolt.lock near " + error.position() + ": " + error.getMessage(),
                        "Fix the TOML syntax in zolt.lock, or run `zolt resolve` to regenerate it.");
            }

            int version = LockfileTomlValues.requireInt(result, "version");
            if (version < MIN_SUPPORTED_VERSION || version > ZoltLockfile.CURRENT_VERSION) {
                throw unsupportedVersion(version);
            }

            ZoltLockfile lockfile = new ZoltLockfile(
                    version,
                    LockfileTomlValues.optionalString(result, "aliasFingerprint"),
                    LockfileTomlValues.optionalString(result, "projectResolutionFingerprint"),
                    LockfileTomlValues.optionalStringArray(result, "projectResolutionInputFingerprints"),
                    packageCodec.packages(result.getArray("package")),
                    conflicts(result.getArray("conflict")),
                    policyEffects(result.getArray("policy")),
                    memberGraphs(result.getArray("memberGraph")),
                    LockfileTomlValues.optionalString(
                            result, "workspaceResolutionInputFingerprint"),
                    dependencyRoots(result.getArray("dependencyRoot")));
            LockfileDependencyRootCompleteness.violation(lockfile).ifPresent(violation -> {
                throw new LockfileReadException(violation + " Run `zolt resolve` to regenerate the lockfile.");
            });
            return lockfile;
        } catch (TomlInvalidTypeException exception) {
            throw new LockfileReadException(
                    "Invalid value type in zolt.lock: "
                            + exception.getMessage()
                            + ". Run `zolt resolve` to regenerate the lockfile.",
                    exception);
        } catch (LockDependencyGraphException exception) {
            throw new LockfileReadException(
                    "Invalid dependency graph in zolt.lock: " + exception.getMessage(),
                    exception);
        }
    }

    private static List<LockDependencyRoot> dependencyRoots(TomlArray rootArray) {
        if (rootArray == null) {
            return List.of();
        }
        List<LockDependencyRoot> roots = new ArrayList<>();
        for (int index = 0; index < rootArray.size(); index++) {
            TomlTable table = rootArray.getTable(index);
            if (table == null) {
                throw new LockfileReadException(
                        "Invalid dependencyRoot entry at index " + index + " in zolt.lock.");
            }
            boolean publishOnly = LockfileTomlValues.optionalBoolean(table, "publishOnly");
            try {
                roots.add(new LockDependencyRoot(
                        LockfileTomlValues.requireString(table, "member"),
                        LockfileTomlValues.packageId(LockfileTomlValues.requireString(table, "id")),
                        LockfileTomlValues.requireString(table, "version"),
                        dependencyRootVariant(table),
                        lane(LockfileTomlValues.requireString(table, "lane")),
                        LockfileTomlValues.optionalString(table, "resolvedScope")
                                .map(ZoltLockfileReader::dependencyRootScope),
                        LockfileTomlValues.optionalBoolean(table, "optional"),
                        publishOnly));
            } catch (IllegalArgumentException exception) {
                throw new LockfileReadException(
                        "Invalid dependencyRoot entry at index " + index + " in zolt.lock: "
                                + exception.getMessage(),
                        exception);
            }
        }
        return List.copyOf(roots);
    }

    private static LockArtifactVariant dependencyRootVariant(TomlTable table) {
        if (!table.contains("variant")) {
            return LockArtifactVariant.defaultVariant();
        }
        String raw = LockfileTomlValues.requireString(table, "variant");
        LockArtifactVariant variant = LockArtifactVariant.fromKey(raw);
        if (!variant.key().equals(raw)) {
            throw new IllegalArgumentException(
                    "dependencyRoot variant `" + raw + "` is not a canonical artifact variant key");
        }
        return variant;
    }

    private static List<LockConflict> conflicts(TomlArray conflictArray) {
        if (conflictArray == null) {
            return List.of();
        }

        List<LockConflict> conflicts = new ArrayList<>();
        for (int index = 0; index < conflictArray.size(); index++) {
            TomlTable table = conflictArray.getTable(index);
            if (table == null) {
                throw new LockfileReadException("Invalid conflict entry at index " + index + " in zolt.lock.");
            }
            conflicts.add(new LockConflict(
                    LockfileTomlValues.packageId(LockfileTomlValues.requireString(table, "id")),
                    LockfileTomlValues.requireString(table, "selected"),
                    LockfileTomlValues.stringArray(table, "requested"),
                    reason(LockfileTomlValues.requireString(table, "reason")),
                    LockfileTomlValues.optionalString(table, "tool"),
                    LockfileTomlValues.optionalString(table, "variant")
                            .map(LockArtifactVariant::fromKey),
                    LockfileTomlValues.optionalStringArray(
                            table, "members")));
        }
        return conflicts;
    }

    private static List<LockPolicyEffect> policyEffects(TomlArray policyArray) {
        if (policyArray == null) {
            return List.of();
        }

        List<LockPolicyEffect> policyEffects = new ArrayList<>();
        for (int index = 0; index < policyArray.size(); index++) {
            TomlTable table = policyArray.getTable(index);
            if (table == null) {
                throw new LockfileReadException("Invalid policy entry at index " + index + " in zolt.lock.");
            }
            policyEffects.add(new LockPolicyEffect(
                    LockfileTomlValues.requireString(table, "kind"),
                    LockfileTomlValues.packageId(LockfileTomlValues.requireString(table, "id")),
                    LockfileTomlValues.optionalString(table, "requested"),
                    LockfileTomlValues.optionalString(table, "source"),
                    LockfileTomlValues.requireString(table, "policy")));
        }
        return policyEffects;
    }

    private static List<LockMemberGraph> memberGraphs(TomlArray graphArray) {
        if (graphArray == null) {
            return List.of();
        }
        List<LockMemberGraph> memberGraphs = new ArrayList<>();
        for (int index = 0; index < graphArray.size(); index++) {
            TomlTable table = graphArray.getTable(index);
            if (table == null) {
                throw new LockfileReadException(
                        "Invalid memberGraph entry at index " + index + " in zolt.lock.");
            }
            memberGraphs.add(new LockMemberGraph(
                    LockfileTomlValues.requireString(table, "member"),
                    LockfileTomlValues.packageId(LockfileTomlValues.requireString(table, "id")),
                    LockfileTomlValues.requireString(table, "version"),
                    LockfileTomlValues.optionalString(table, "variant")
                            .map(LockArtifactVariant::fromKey)
                            .orElseGet(LockArtifactVariant::defaultVariant),
                    scope(LockfileTomlValues.requireString(table, "scope")),
                    LockfileTomlValues.stringArray(table, "dependencies"),
                    LockfileTomlValues.optionalStringArray(table, "policies"),
                    LockfileTomlValues.optionalBoolean(table, "declaredOptional"),
                    table.contains("optionalOnly")
                            ? LockfileTomlValues.optionalBoolean(table, "optionalOnly")
                            : LockfileTomlValues.optionalBoolean(table, "optional")));
        }
        return List.copyOf(memberGraphs);
    }

    private static DependencyScope scope(String value) {
        for (DependencyScope scope : DependencyScope.values()) {
            if (scope.lockfileName().equals(value)) {
                return scope;
            }
        }
        throw new LockfileReadException(
                "Invalid memberGraph scope `" + value + "` in zolt.lock.");
    }

    private static DependencyLane lane(String value) {
        return switch (value) {
            case "api" -> DependencyLane.API;
            case "implementation" -> DependencyLane.IMPLEMENTATION;
            case "runtime" -> DependencyLane.RUNTIME;
            case "provided" -> DependencyLane.PROVIDED;
            case "dev" -> DependencyLane.DEV;
            case "test" -> DependencyLane.TEST;
            case "processor" -> DependencyLane.PROCESSOR;
            case "test-processor" -> DependencyLane.TEST_PROCESSOR;
            default -> throw new LockfileReadException(
                    "Invalid dependencyRoot lane `" + value + "` in zolt.lock.");
        };
    }

    private static DependencyScope dependencyRootScope(String value) {
        for (DependencyScope scope : DependencyScope.values()) {
            if (scope.lockfileName().equals(value)) {
                return scope;
            }
        }
        throw new LockfileReadException(
                "Invalid dependencyRoot resolvedScope `" + value + "` in zolt.lock.");
    }

    private static ConflictSelectionReason reason(String value) {
        return switch (value) {
            case "direct dependency wins" -> ConflictSelectionReason.DIRECT_DEPENDENCY;
            case "newest version wins" -> ConflictSelectionReason.NEWEST_VERSION;
            case "selected materialized graph wins" -> ConflictSelectionReason.SELECTED_GRAPH;
            default -> throw new LockfileReadException(
                    "Invalid conflict reason `" + value + "` in zolt.lock.");
        };
    }

    private static LockfileReadException unsupportedVersion(int version) {
        if (version > ZoltLockfile.CURRENT_VERSION) {
            return LockfileReadException.actionable(
                    "zolt.lock version "
                            + version
                            + " is newer than this Zolt supports (current "
                            + ZoltLockfile.CURRENT_VERSION
                            + ").",
                    "Upgrade Zolt, then run `zolt resolve --locked` to verify the lockfile.");
        }
        return LockfileReadException.actionable(
                "zolt.lock version "
                        + version
                        + " is older than this Zolt supports (current "
                        + ZoltLockfile.CURRENT_VERSION
                        + ").",
                "Run `zolt resolve` with this Zolt version to regenerate the lockfile.");
    }
}
