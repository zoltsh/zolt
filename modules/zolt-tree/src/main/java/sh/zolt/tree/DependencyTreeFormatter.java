package sh.zolt.tree;

import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.dependency.ConflictSelectionReason;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DependencyTreeFormatter {
    public String format(ProjectConfig config, ZoltLockfile lockfile) {
        LockDependencyIndex packages = new LockDependencyIndex(lockfile.packages());
        Map<String, LockConflict> conflicts = conflictsByPackage(lockfile);
        List<LockPackage> directPackages = lockfile.packages().stream()
                .filter(LockPackage::direct)
                .sorted(Comparator.comparing(DependencyTreeFormatter::coordinate))
                .toList();

        StringBuilder output = new StringBuilder();
        output.append(config.project().group())
                .append(':')
                .append(config.project().name())
                .append(':')
                .append(config.project().version())
                .append('\n');
        for (int index = 0; index < directPackages.size(); index++) {
            writePackage(
                    output,
                    directPackages.get(index),
                    packages,
                    conflicts,
                    "",
                    index == directPackages.size() - 1,
                    List.of());
        }
        writePolicyEffects(output, lockfile);
        return output.toString();
    }

    private static void writePackage(
            StringBuilder output,
            LockPackage lockPackage,
            LockDependencyIndex packages,
            Map<String, LockConflict> conflicts,
            String prefix,
            boolean last,
            List<String> ancestors) {
        String coordinate = coordinate(lockPackage);
        output.append(prefix).append(last ? "\\- " : "+- ").append(coordinate);
        LockConflict conflict = conflicts.get(qualifiedKey(lockPackage));
        if (conflict != null) {
            output.append(" (conflict: selected ")
                    .append(conflict.selectedVersion())
                    .append("; requested ")
                    .append(String.join(", ", conflict.requestedVersions().stream().sorted().toList()))
                    .append("; ")
                    .append(reason(conflict.reason()))
                    .append(')');
        }
        appendPolicies(output, lockPackage);
        if (ancestors.contains(coordinate)) {
            output.append(" (cycle)");
        }
        output.append('\n');

        if (ancestors.contains(coordinate)) {
            return;
        }

        List<LockPackage> dependencies = lockPackage.dependencies().stream()
                .sorted()
                .map(edge -> packages.resolveGraphEdge(edge, "zolt resolve").orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        String childPrefix = prefix + (last ? "   " : "|  ");
        List<String> nextAncestors = new ArrayList<>(ancestors);
        nextAncestors.add(coordinate);
        for (int index = 0; index < dependencies.size(); index++) {
            writePackage(
                    output,
                    dependencies.get(index),
                    packages,
                    conflicts,
                    childPrefix,
                    index == dependencies.size() - 1,
                    nextAncestors);
        }
    }

    private static Map<String, LockConflict> conflictsByPackage(ZoltLockfile lockfile) {
        Map<String, LockConflict> conflicts = new LinkedHashMap<>();
        lockfile.conflicts().stream()
                .sorted(Comparator.comparing(DependencyTreeFormatter::qualifiedKey))
                .forEach(conflict -> conflicts.put(qualifiedKey(conflict), conflict));
        return conflicts;
    }

    private static String coordinate(LockPackage lockPackage) {
        LockArtifactVariant variant = LockArtifactVariant.of(lockPackage);
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + (variant.isDefault() ? "" : ":" + variant.key());
    }

    private static String qualifiedKey(LockPackage lockPackage) {
        return lockPackage.packageId() + ":" + LockArtifactVariant.of(lockPackage).key();
    }

    private static String qualifiedKey(LockConflict conflict) {
        return conflict.packageId()
                + ":"
                + conflict.variant().map(LockArtifactVariant::key).orElse(LockArtifactVariant.defaultVariant().key());
    }

    private static String reason(ConflictSelectionReason reason) {
        return switch (reason) {
            case DIRECT_DEPENDENCY -> "direct dependency wins";
            case NEWEST_VERSION -> "newest version wins";
            case SELECTED_GRAPH -> "selected materialized graph wins";
        };
    }

    private static void appendPolicies(StringBuilder output, LockPackage lockPackage) {
        if (lockPackage.policies().isEmpty()) {
            return;
        }
        output.append(" (policy: ")
                .append(String.join("; ", lockPackage.policies().stream().sorted().toList()))
                .append(')');
    }

    private static void writePolicyEffects(StringBuilder output, ZoltLockfile lockfile) {
        List<LockPolicyEffect> exclusionEffects = lockfile.policyEffects().stream()
                .filter(DependencyTreeFormatter::exclusion)
                .sorted(Comparator.comparing(DependencyTreeFormatter::policyEffectSortKey))
                .toList();
        if (exclusionEffects.isEmpty()) {
            return;
        }
        output.append("Policy effects\n");
        for (LockPolicyEffect effect : exclusionEffects) {
            output.append("- ")
                    .append(formatPolicyEffect(effect))
                    .append('\n');
        }
    }

    private static boolean exclusion(LockPolicyEffect effect) {
        return "global-exclusion".equals(effect.kind()) || "edge-exclusion".equals(effect.kind());
    }

    private static String formatPolicyEffect(LockPolicyEffect effect) {
        StringBuilder output = new StringBuilder();
        output.append(effect.kind())
                .append(' ')
                .append(effect.packageId());
        effect.requestedVersion().ifPresent(version -> output.append(':').append(version));
        effect.source().ifPresent(source -> output.append(" from ").append(source));
        output.append(": ").append(effect.policy());
        return output.toString();
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
}
