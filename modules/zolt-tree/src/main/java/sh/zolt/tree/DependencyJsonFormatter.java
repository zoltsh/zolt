package sh.zolt.tree;

import static sh.zolt.tree.DependencyJsonFields.booleanField;
import static sh.zolt.tree.DependencyJsonFields.comma;
import static sh.zolt.tree.DependencyJsonFields.indent;
import static sh.zolt.tree.DependencyJsonFields.intField;
import static sh.zolt.tree.DependencyJsonFields.optionalStringField;
import static sh.zolt.tree.DependencyJsonFields.stringArrayField;
import static sh.zolt.tree.DependencyJsonFields.stringField;

import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.dependency.PackageId;
import sh.zolt.dependency.ConflictSelectionReason;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class DependencyJsonFormatter {
    public String tree(ProjectConfig config, ZoltLockfile lockfile) {
        return tree(config, lockfile, ".");
    }

    /**
     * The tree of the project identified by {@code member}: {@code .} for a standalone project, or a
     * member path, whose packages and roots design §4.5 projects out of the one authoritative
     * workspace lock. Conflicts and policy effects stay lock-wide facts and carry their own member
     * attribution.
     */
    public String tree(ProjectConfig config, ZoltLockfile lockfile, String member) {
        DependencyRootProjection roots = DependencyRootProjection.of(lockfile, member);
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        intField(json, 1, "schemaVersion", 1, true);
        stringField(json, 1, "command", "tree", true);
        project(json, config);
        comma(json);
        packages(json, memberPackages(lockfile, member), roots);
        comma(json);
        stringArrayField(json, 1, "roots", roots.graphRootCoordinates(), true);
        conflicts(json, lockfile.conflicts());
        comma(json);
        policyEffects(json, lockfile.policyEffects().stream()
                .filter(PolicyEffectScope.of(lockfile, member))
                .toList());
        json.append("\n}\n");
        return json.toString();
    }

    public String why(ProjectConfig config, ZoltLockfile lockfile, PackageId target) {
        return why(config, lockfile, target, ".");
    }

    /** Why {@code target} is present for the project identified by {@code member} (design §4.5). */
    public String why(ProjectConfig config, ZoltLockfile lockfile, PackageId target, String member) {
        DependencyRootProjection roots = DependencyRootProjection.of(lockfile, member);
        Optional<DependencyRootProjection.ResolvedPath> resolvedPath = roots.pathTo(target);
        if (resolvedPath.isEmpty() && roots.publishOnlyRoot(target).isPresent()) {
            throw new DependencyWhyException(
                    "Package " + target
                            + " is a publish-only authored dependency, which why JSON schema 1 cannot represent. Run `zolt why "
                            + target + "` without --format json to inspect its lane.");
        }
        Optional<List<LockPackage>> path = resolvedPath.map(DependencyRootProjection.ResolvedPath::packages);
        List<LockPolicyEffect> effects = path.isPresent()
                ? policyEffects(lockfile, target)
                : exclusionEffects(lockfile, target);
        List<LockConflict> conflicts = conflicts(lockfile, target);
        if (path.isEmpty() && effects.isEmpty() && conflicts.isEmpty()) {
            throw new DependencyWhyException(
                    "Package " + target + " is not present in zolt.lock. Run `"
                            + roots.regenerateCommand() + "` after adding it or check the package id.");
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        intField(json, 1, "schemaVersion", 1, true);
        stringField(json, 1, "command", "why", true);
        project(json, config);
        comma(json);
        stringField(json, 1, "target", target.toString(), true);
        stringField(json, 1, "status", path.isPresent() ? "present" : "excluded", true);
        path(json, path.orElse(List.of()), roots);
        comma(json);
        conflicts(json, conflicts);
        comma(json);
        policyEffects(json, effects);
        json.append("\n}\n");
        return json.toString();
    }

    private static void project(StringBuilder json, ProjectConfig config) {
        indent(json, 1).append("\"project\": {\n");
        stringField(json, 2, "group", config.project().group(), true);
        stringField(json, 2, "name", config.project().name(), true);
        stringField(json, 2, "version", config.project().version(), true);
        stringField(json, 2, "coordinate", projectCoordinate(config), false);
        indent(json, 1).append("}");
    }

    /** The packages of one member's graph; a standalone lock holds exactly that project's graph. */
    private static List<LockPackage> memberPackages(ZoltLockfile lockfile, String member) {
        if (".".equals(member)) {
            return lockfile.packages();
        }
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.members().contains(member))
                .toList();
    }

    private static void packages(
            StringBuilder json,
            List<LockPackage> packages,
            DependencyRootProjection roots) {
        indent(json, 1).append("\"packages\": [");
        List<LockPackage> sorted = packages.stream()
                .sorted(Comparator.comparing(DependencyJsonFormatter::coordinate))
                .toList();
        if (!sorted.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < sorted.size(); index++) {
                LockPackage lockPackage = sorted.get(index);
                packageObject(json, 2, lockPackage, roots.selects(lockPackage));
                if (index + 1 < sorted.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static void packageObject(
            StringBuilder json,
            int level,
            LockPackage lockPackage,
            boolean direct) {
        indent(json, level).append("{\n");
        stringField(json, level + 1, "id", lockPackage.packageId().toString(), true);
        stringField(json, level + 1, "version", lockPackage.version(), true);
        stringField(json, level + 1, "coordinate", coordinate(lockPackage), true);
        nonDefaultVariant(lockPackage).ifPresent(variant ->
                stringField(json, level + 1, "variant", variant.key(), true));
        stringField(json, level + 1, "scope", lockPackage.scope().lockfileName(), true);
        booleanField(json, level + 1, "direct", direct, true);
        stringArrayField(json, level + 1, "dependencies", lockPackage.dependencies().stream().sorted().toList(), true);
        stringArrayField(json, level + 1, "policies", lockPackage.policies().stream().sorted().toList(), false);
        indent(json, level).append("}");
    }

    private static void path(
            StringBuilder json,
            List<LockPackage> path,
            DependencyRootProjection roots) {
        indent(json, 1).append("\"path\": [");
        if (!path.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < path.size(); index++) {
                LockPackage lockPackage = path.get(index);
                packageObject(json, 2, lockPackage, roots.selects(lockPackage));
                if (index + 1 < path.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static void conflicts(StringBuilder json, List<LockConflict> conflicts) {
        indent(json, 1).append("\"conflicts\": [");
        List<LockConflict> sorted = conflicts.stream()
                .sorted(Comparator
                        .comparing((LockConflict conflict) -> conflict.packageId().toString())
                        .thenComparing(conflict -> conflict.variant().map(LockArtifactVariant::key).orElse("")))
                .toList();
        if (!sorted.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < sorted.size(); index++) {
                LockConflict conflict = sorted.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "id", conflict.packageId().toString(), true);
                conflict.variant().ifPresent(variant ->
                        stringField(json, 3, "variant", variant.key(), true));
                conflict.toolGroup().ifPresent(tool ->
                        stringField(json, 3, "tool", tool, true));
                stringField(json, 3, "selected", conflict.selectedVersion(), true);
                stringArrayField(json, 3, "requested", conflict.requestedVersions().stream().sorted().toList(), true);
                stringField(json, 3, "reason", reason(conflict.reason()), !conflict.members().isEmpty());
                if (!conflict.members().isEmpty()) {
                    stringArrayField(
                            json,
                            3,
                            "members",
                            conflict.members().stream().sorted().toList(),
                            false);
                }
                indent(json, 2).append("}");
                if (index + 1 < sorted.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static void policyEffects(StringBuilder json, List<LockPolicyEffect> policyEffects) {
        indent(json, 1).append("\"policyEffects\": [");
        List<LockPolicyEffect> sorted = policyEffects.stream()
                .sorted(Comparator.comparing(DependencyJsonFormatter::policyEffectSortKey))
                .toList();
        if (!sorted.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < sorted.size(); index++) {
                LockPolicyEffect effect = sorted.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "kind", effect.kind(), true);
                stringField(json, 3, "id", effect.packageId().toString(), true);
                optionalStringField(json, 3, "requested", effect.requestedVersion(), true);
                optionalStringField(json, 3, "source", effect.source(), true);
                stringField(json, 3, "policy", effect.policy(), false);
                indent(json, 2).append("}");
                if (index + 1 < sorted.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static List<LockPolicyEffect> exclusionEffects(ZoltLockfile lockfile, PackageId target) {
        return lockfile.policyEffects().stream()
                .filter(effect -> effect.packageId().equals(target))
                .filter(DependencyJsonFormatter::exclusion)
                .sorted(Comparator.comparing(DependencyJsonFormatter::policyEffectSortKey))
                .toList();
    }

    private static List<LockPolicyEffect> policyEffects(ZoltLockfile lockfile, PackageId target) {
        return lockfile.policyEffects().stream()
                .filter(effect -> effect.packageId().equals(target))
                .sorted(Comparator.comparing(DependencyJsonFormatter::policyEffectSortKey))
                .toList();
    }

    private static List<LockConflict> conflicts(ZoltLockfile lockfile, PackageId target) {
        return lockfile.conflicts().stream()
                .filter(conflict -> conflict.packageId().equals(target))
                .sorted(Comparator.comparing(conflict ->
                        conflict.variant().map(LockArtifactVariant::key).orElse("")))
                .toList();
    }

    private static boolean exclusion(LockPolicyEffect effect) {
        return "global-exclusion".equals(effect.kind()) || "edge-exclusion".equals(effect.kind());
    }

    private static String coordinate(LockPackage lockPackage) {
        LockArtifactVariant variant = LockArtifactVariant.of(lockPackage);
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + (variant.isDefault() ? "" : ":" + variant.key());
    }

    private static Optional<LockArtifactVariant> nonDefaultVariant(LockPackage lockPackage) {
        LockArtifactVariant variant = LockArtifactVariant.of(lockPackage);
        return variant.isDefault() ? Optional.empty() : Optional.of(variant);
    }

    private static String projectCoordinate(ProjectConfig config) {
        return config.project().group() + ":" + config.project().name() + ":" + config.project().version();
    }

    private static String policyEffectSortKey(LockPolicyEffect effect) {
        return effect.kind()
                + ":"
                + effect.packageId()
                + ":"
                + effect.requestedVersion().orElse("")
                + ":"
                + effect.source().orElse("")
                + ":"
                + effect.policy();
    }

    private static String reason(ConflictSelectionReason reason) {
        return switch (reason) {
            case DIRECT_DEPENDENCY -> "direct dependency wins";
            case NEWEST_VERSION -> "newest version wins";
            case SELECTED_GRAPH -> "selected materialized graph wins";
        };
    }
}
