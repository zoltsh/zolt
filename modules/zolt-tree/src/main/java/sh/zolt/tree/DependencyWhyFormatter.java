package sh.zolt.tree;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.dependency.PackageId;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class DependencyWhyFormatter {
    public String format(ProjectConfig config, ZoltLockfile lockfile, PackageId target) {
        Optional<List<LockPackage>> resolvedPath = pathTo(lockfile, target);
        if (resolvedPath.isEmpty()) {
            List<LockPolicyEffect> exclusionEffects = exclusionEffects(lockfile, target);
            if (!exclusionEffects.isEmpty()) {
                return formatExcluded(config, target, exclusionEffects);
            }
            throw new DependencyWhyException(
                    "Package " + target + " is not present in zolt.lock. Run `zolt resolve` after adding it or check the package id.");
        }
        List<LockPackage> path = resolvedPath.orElseThrow();
        Optional<LockConflict> targetConflict = conflictFor(lockfile, path.getLast());
        StringBuilder output = new StringBuilder();
        output.append(config.project().group())
                .append(':')
                .append(config.project().name())
                .append(':')
                .append(config.project().version())
                .append('\n');
        for (int index = 0; index < path.size(); index++) {
            output.append("   ".repeat(index))
                    .append("\\- ")
                    .append(coordinate(path.get(index)));
            if (path.get(index).packageId().equals(target)) {
                targetConflict.ifPresent(conflict -> appendConflict(output, conflict));
            }
            appendPolicies(output, path.get(index));
            output.append('\n');
        }
        return output.toString();
    }

    private static String formatExcluded(
            ProjectConfig config,
            PackageId target,
            List<LockPolicyEffect> effects) {
        StringBuilder output = new StringBuilder();
        output.append(config.project().group())
                .append(':')
                .append(config.project().name())
                .append(':')
                .append(config.project().version())
                .append('\n');
        output.append("\\- ")
                .append(target)
                .append(" (excluded by dependency policy)")
                .append('\n');
        for (LockPolicyEffect effect : effects) {
            output.append("   \\- ")
                    .append(formatPolicyEffect(effect))
                    .append('\n');
        }
        return output.toString();
    }

    private static Optional<List<LockPackage>> pathTo(ZoltLockfile lockfile, PackageId target) {
        LockDependencyIndex packages = new LockDependencyIndex(lockfile.packages());
        ArrayDeque<PathItem> queue = new ArrayDeque<>();
        lockfile.packages().stream()
                .filter(LockPackage::direct)
                .sorted(Comparator.comparing(DependencyWhyFormatter::coordinate))
                .forEach(lockPackage -> queue.add(new PathItem(lockPackage, List.of(lockPackage))));

        while (!queue.isEmpty()) {
            PathItem item = queue.removeFirst();
            if (item.lockPackage().packageId().equals(target)) {
                return Optional.of(item.path());
            }
            item.lockPackage().dependencies().stream()
                    .sorted()
                    .map(edge -> packages.resolveGraphEdge(edge, "zolt resolve").orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .filter(dependency -> !contains(item.path(), dependency))
                    .forEach(dependency -> queue.add(new PathItem(
                            dependency,
                            append(item.path(), dependency))));
        }

        return Optional.empty();
    }

    private static String coordinate(LockPackage lockPackage) {
        LockArtifactVariant variant = LockArtifactVariant.of(lockPackage);
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + (variant.isDefault() ? "" : ":" + variant.key());
    }

    private static boolean contains(List<LockPackage> path, LockPackage candidate) {
        return path.stream().anyMatch(lockPackage -> coordinate(lockPackage).equals(coordinate(candidate)));
    }

    private static List<LockPackage> append(List<LockPackage> path, LockPackage lockPackage) {
        java.util.ArrayList<LockPackage> updated = new java.util.ArrayList<>(path);
        updated.add(lockPackage);
        return List.copyOf(updated);
    }

    private static void appendPolicies(StringBuilder output, LockPackage lockPackage) {
        if (lockPackage.policies().isEmpty()) {
            return;
        }
        output.append(" (policy: ")
                .append(String.join("; ", lockPackage.policies().stream().sorted().toList()))
                .append(')');
    }

    private static Optional<LockConflict> conflictFor(ZoltLockfile lockfile, LockPackage target) {
        LockArtifactVariant targetVariant = LockArtifactVariant.of(target);
        return lockfile.conflicts().stream()
                .filter(conflict -> conflict.packageId().equals(target.packageId()))
                .filter(conflict -> conflict.variant()
                        .orElse(LockArtifactVariant.defaultVariant())
                        .equals(targetVariant))
                .findFirst();
    }

    private static void appendConflict(StringBuilder output, LockConflict conflict) {
        output.append(" (conflict: selected ")
                .append(conflict.selectedVersion())
                .append("; requested ")
                .append(String.join(", ", conflict.requestedVersions().stream().sorted().toList()))
                .append("; ")
                .append(reason(conflict.reason()))
                .append(')');
    }

    private static List<LockPolicyEffect> exclusionEffects(ZoltLockfile lockfile, PackageId target) {
        return lockfile.policyEffects().stream()
                .filter(effect -> effect.packageId().equals(target))
                .filter(DependencyWhyFormatter::exclusion)
                .sorted(Comparator.comparing(DependencyWhyFormatter::policyEffectSortKey))
                .toList();
    }

    private static boolean exclusion(LockPolicyEffect effect) {
        return "global-exclusion".equals(effect.kind()) || "edge-exclusion".equals(effect.kind());
    }

    private static String formatPolicyEffect(LockPolicyEffect effect) {
        StringBuilder output = new StringBuilder();
        output.append(effect.kind());
        effect.requestedVersion().ifPresent(version -> output.append(" requested ").append(version));
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

    private static String reason(ConflictSelectionReason reason) {
        return switch (reason) {
            case DIRECT_DEPENDENCY -> "direct dependency wins";
            case NEWEST_VERSION -> "newest version wins";
        };
    }

    private record PathItem(LockPackage lockPackage, List<LockPackage> path) {
        private PathItem {
            path = List.copyOf(path);
        }
    }
}
