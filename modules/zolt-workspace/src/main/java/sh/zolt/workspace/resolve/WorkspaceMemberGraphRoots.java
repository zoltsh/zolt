package sh.zolt.workspace.resolve;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.service.Workspace;

/** Computes the exact locked roots of one member's dependency graph. */
public final class WorkspaceMemberGraphRoots {
    private final WorkspaceMemberPolicyLockProjection projection =
            new WorkspaceMemberPolicyLockProjection();

    /**
     * User declarations retain their lockfile directness. Injected tooling remains indirect but is a
     * graph root when no other package in the member-qualified tool closure points to it.
     */
    public List<String> roots(
            String memberPath,
            ProjectConfig effectiveConfig,
            ZoltLockfile aggregate,
            Workspace workspace) {
        List<LockPackage> packages =
                projection.project(memberPath, effectiveConfig, aggregate, workspace).packages();
        LockDependencyIndex index = new LockDependencyIndex(aggregate.packages());
        Set<String> children = packages.stream()
                .flatMap(lockPackage -> lockPackage.dependencies().stream())
                .map(edge -> index.resolveGraphEdge(edge, "zolt resolve --workspace").orElseThrow())
                .map(LockDependencyEdge::of)
                .map(LockDependencyEdge::encode)
                .collect(Collectors.toSet());
        return packages.stream()
                .filter(lockPackage -> lockPackage.direct()
                        || (toolingScope(lockPackage.scope())
                                && !children.contains(LockDependencyEdge.of(lockPackage).encode())))
                .map(LockDependencyEdge::of)
                .map(LockDependencyEdge::encode)
                .sorted()
                .toList();
    }

    private static boolean toolingScope(DependencyScope scope) {
        return switch (scope) {
            case TOOL_COVERAGE, TOOL_EXEC, TOOL_OPENAPI, TOOL_PROTOBUF, TOOL_SPRING_AOT -> true;
            default -> false;
        };
    }
}
