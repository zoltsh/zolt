package sh.zolt.tree;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.dependency.PackageId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class DependencyWhyFormatter {
    public String format(ProjectConfig config, ZoltLockfile lockfile, PackageId target) {
        return format(config, lockfile, target, ".");
    }

    /**
     * Why {@code target} is present for the project identified by {@code member}: {@code .} for a
     * standalone project, or a member path, whose graph design §4.5 projects out of the one
     * authoritative workspace lock.
     */
    public String format(ProjectConfig config, ZoltLockfile lockfile, PackageId target, String member) {
        DependencyRootProjection roots = DependencyRootProjection.of(lockfile, member);
        Optional<DependencyRootProjection.ResolvedPath> resolvedPath = roots.pathTo(target);
        if (resolvedPath.isEmpty()) {
            Optional<DependencyRootProjection.Root> publishOnly = roots.publishOnlyRoot(target);
            if (publishOnly.isPresent()) {
                return formatPublishOnly(config, publishOnly.orElseThrow());
            }
            List<LockPolicyEffect> exclusionEffects = exclusionEffects(lockfile, target);
            if (!exclusionEffects.isEmpty()) {
                return formatExcluded(config, target, exclusionEffects);
            }
            throw new DependencyWhyException(
                    "Package " + target + " is not present in zolt.lock. Run `"
                            + roots.regenerateCommand() + "` after adding it or check the package id.");
        }
        DependencyRootProjection.ResolvedPath resolved = resolvedPath.orElseThrow();
        List<LockPackage> path = resolved.packages();
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
            if (index == 0) {
                output.append(" (").append(resolved.root().annotation()).append(')');
            }
            if (path.get(index).packageId().equals(target)) {
                targetConflict.ifPresent(conflict -> appendConflict(output, conflict));
            }
            appendPolicies(output, path.get(index));
            output.append('\n');
        }
        return output.toString();
    }

    private static String formatPublishOnly(
            ProjectConfig config,
            DependencyRootProjection.Root root) {
        return config.project().group() + ":"
                + config.project().name() + ":"
                + config.project().version() + "\n"
                + "\\- " + root.coordinate()
                + " (" + root.annotation() + ")\n";
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

    private static String coordinate(LockPackage lockPackage) {
        LockArtifactVariant variant = LockArtifactVariant.of(lockPackage);
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + (variant.isDefault() ? "" : ":" + variant.key());
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
            case SELECTED_GRAPH -> "selected materialized graph wins";
        };
    }
}
