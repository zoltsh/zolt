package sh.zolt.tree;

import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import java.util.Comparator;
import java.util.List;

public final class DependencyTreeFormatter {
    public String format(ProjectConfig config, ZoltLockfile lockfile) {
        DependencyTreeLines lines = new DependencyTreeLines(
                new LockDependencyIndex(lockfile.packages()),
                DependencyTreeLines.conflictsByPackage(lockfile),
                DependencyTreeLines.lockView(),
                "zolt resolve");
        DependencyRootProjection roots = DependencyRootProjection.standalone(lockfile);

        StringBuilder output = new StringBuilder();
        output.append(config.project().group())
                .append(':')
                .append(config.project().name())
                .append(':')
                .append(config.project().version())
                .append('\n');
        lines.writeRoots(output, roots.rootsFor("."));
        writePolicyEffects(output, lockfile);
        return output.toString();
    }

    static void writePolicyEffects(StringBuilder output, ZoltLockfile lockfile) {
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
