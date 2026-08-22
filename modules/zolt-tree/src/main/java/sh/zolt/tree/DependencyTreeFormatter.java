package sh.zolt.tree;

import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public final class DependencyTreeFormatter {
    public String format(ProjectConfig config, ZoltLockfile lockfile) {
        return format(config, lockfile, ".");
    }

    /**
     * The tree of the project identified by {@code member} in {@code lockfile}: {@code .} for a
     * standalone project, or a member path, whose graph design §4.5 projects out of the one
     * authoritative workspace lock.
     */
    public String format(ProjectConfig config, ZoltLockfile lockfile, String member) {
        DependencyRootProjection roots = DependencyRootProjection.of(lockfile, member);
        DependencyTreeLines lines = new DependencyTreeLines(
                new LockDependencyIndex(lockfile.packages()),
                DependencyTreeLines.conflictsByPackage(lockfile),
                DependencyTreeLines.lockView(),
                roots.regenerateCommand());

        StringBuilder output = new StringBuilder();
        output.append(config.project().group())
                .append(':')
                .append(config.project().name())
                .append(':')
                .append(config.project().version())
                .append('\n');
        lines.writeRoots(output, roots.rootsFor(member));
        writePolicyEffects(output, lockfile, PolicyEffectScope.of(lockfile, member));
        return output.toString();
    }

    static void writePolicyEffects(StringBuilder output, ZoltLockfile lockfile) {
        writePolicyEffects(output, lockfile, effect -> true);
    }

    private static void writePolicyEffects(
            StringBuilder output,
            ZoltLockfile lockfile,
            Predicate<LockPolicyEffect> scope) {
        List<LockPolicyEffect> exclusionEffects = lockfile.policyEffects().stream()
                .filter(DependencyTreeFormatter::exclusion)
                .filter(scope)
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
