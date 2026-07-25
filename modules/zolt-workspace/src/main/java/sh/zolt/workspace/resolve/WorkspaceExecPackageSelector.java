package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates tool-exec candidates without version mediation. Each named tool keeps its isolated
 * version; candidates collapse only on package, version, and artifact variant.
 */
final class WorkspaceExecPackageSelector {
    private WorkspaceExecPackageSelector() {
    }

    static List<LockPackage> select(List<LockPackage> candidates) {
        Map<String, List<LockPackage>> byIdentity = new LinkedHashMap<>();
        candidates.stream()
                .sorted(Comparator.comparing(WorkspaceExecPackageSelector::key))
                .forEach(candidate -> byIdentity
                        .computeIfAbsent(key(candidate), ignored -> new ArrayList<>())
                        .add(candidate));
        return byIdentity.values().stream()
                .map(WorkspaceExecPackageSelector::merge)
                .toList();
    }

    private static String key(LockPackage lockPackage) {
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + ":"
                + LockArtifactVariant.of(lockPackage).key();
    }

    private static LockPackage merge(List<LockPackage> candidates) {
        WorkspaceArtifactIdentityVerifier.requireIdenticalBytes(candidates);
        LockPackage template = candidates.getFirst();
        Set<String> toolGroups = new LinkedHashSet<>();
        Set<String> members = new LinkedHashSet<>();
        Set<String> exportedBy = new LinkedHashSet<>();
        Set<String> dependencies = new LinkedHashSet<>();
        Set<String> policies = new LinkedHashSet<>();
        for (LockPackage candidate : candidates) {
            toolGroups.addAll(candidate.toolGroups());
            members.addAll(candidate.members());
            exportedBy.addAll(candidate.exportedBy());
            dependencies.addAll(candidate.dependencies());
            policies.addAll(candidate.policies());
        }
        return new LockPackage(
                template.packageId(),
                template.version(),
                template.source(),
                template.scope(),
                candidates.stream().anyMatch(LockPackage::direct),
                template.jar(),
                template.pom(),
                template.jarSha256(),
                template.pomSha256(),
                template.artifact(),
                template.artifactType(),
                template.artifactSha256(),
                template.workspace(),
                template.workspaceOutput(),
                dependencies.stream().sorted().toList(),
                members.stream().sorted().toList(),
                exportedBy.stream().sorted().toList(),
                policies.stream().sorted().toList(),
                toolGroups.stream().sorted().toList());
    }
}
