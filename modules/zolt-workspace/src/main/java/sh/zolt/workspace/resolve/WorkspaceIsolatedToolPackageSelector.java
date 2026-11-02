package sh.zolt.workspace.resolve;

import sh.zolt.dependency.DependencyScope;
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
 * Aggregates independently resolved tool candidates without version mediation. Each exec tool and
 * compiler closure keeps its isolated version; candidates collapse only within the same
 * scope, package, version, and artifact variant.
 */
final class WorkspaceIsolatedToolPackageSelector {
    private WorkspaceIsolatedToolPackageSelector() {
    }

    static boolean isIsolatedScope(DependencyScope scope) {
        return scope == DependencyScope.TOOL_EXEC
                || scope == DependencyScope.TOOL_GROOVY
                || scope == DependencyScope.TOOL_KOTLIN;
    }

    static List<LockPackage> select(List<LockPackage> candidates) {
        Map<String, List<LockPackage>> byIdentity = new LinkedHashMap<>();
        candidates.stream()
                .sorted(Comparator.comparing(WorkspaceIsolatedToolPackageSelector::key))
                .forEach(candidate -> byIdentity
                        .computeIfAbsent(key(candidate), ignored -> new ArrayList<>())
                        .add(candidate));
        return byIdentity.values().stream()
                .map(WorkspaceIsolatedToolPackageSelector::merge)
                .toList();
    }

    private static String key(LockPackage lockPackage) {
        return lockPackage.scope().lockfileName()
                + ":"
                + lockPackage.packageId()
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
