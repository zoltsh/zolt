package sh.zolt.workspace.resolve;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;

/**
 * Requalifies member-local roots and projects the declarations exposed by legacy workspace edges.
 *
 * <p>{@link WorkspaceProjectEdge} can represent compile, test, processor, and test-processor
 * declarations only. Exported compile edges map to API and other compile edges to implementation;
 * member-local lock roots preserve the remaining authored lanes. Feeding the effective workspace
 * graph directly is a separate migration from this legacy-input boundary.
 */
final class WorkspaceDependencyRootAssembler {
    List<LockDependencyRoot> assemble(
            Workspace workspace,
            List<WorkspaceMemberResolveOutput> memberOutputs,
            List<LockPackage> packages) {
        List<LockDependencyRoot> roots = new ArrayList<>();
        for (WorkspaceMemberResolveOutput output : memberOutputs) {
            output.lockfile().dependencyRoots().stream()
                    .filter(root -> !shadowedByWorkspaceEdge(root, output.member(), workspace.edges()))
                    .map(root -> withMember(root, output.member()))
                    .forEach(roots::add);
        }
        Map<String, WorkspaceMember> members = workspace.members().stream()
                .collect(Collectors.toMap(WorkspaceMember::path, Function.identity()));
        for (WorkspaceProjectEdge edge : workspace.edges()) {
            WorkspaceMember target = members.get(edge.to());
            PackageId packageId = packageId(edge.coordinate());
            DependencyScope scope = scope(edge.scope());
            String version = target.config().project().version();
            requireTarget(packages, packageId, version, scope);
            roots.add(new LockDependencyRoot(
                    edge.from(),
                    packageId,
                    version,
                    null,
                    lane(edge),
                    Optional.of(scope),
                    edge.optional(),
                    false));
        }
        return List.copyOf(roots);
    }

    private static boolean shadowedByWorkspaceEdge(
            LockDependencyRoot root,
            String member,
            List<WorkspaceProjectEdge> edges) {
        if (root.publishOnly() || !root.variant().isDefault() || root.resolvedScope().isEmpty()) {
            return false;
        }
        return edges.stream().anyMatch(edge -> edge.from().equals(member)
                && packageId(edge.coordinate()).equals(root.packageId())
                && lane(edge) == root.lane()
                && scope(edge.scope()) == root.resolvedScope().orElseThrow());
    }

    private static LockDependencyRoot withMember(LockDependencyRoot root, String member) {
        if (!root.member().equals(".")) {
            throw new IllegalStateException(
                    "Member lock dependency root must use member `.` before workspace requalification; found `"
                            + root.member() + "` for `" + root.packageId() + "`.");
        }
        return new LockDependencyRoot(
                member,
                root.packageId(),
                root.version(),
                root.variant(),
                root.lane(),
                root.resolvedScope(),
                root.optional(),
                root.publishOnly());
    }

    private static void requireTarget(
            List<LockPackage> packages,
            PackageId packageId,
            String version,
            DependencyScope scope) {
        if (packages.stream().noneMatch(lockPackage -> packageId.equals(lockPackage.packageId())
                && version.equals(lockPackage.version())
                && scope == lockPackage.scope()
                && lockPackage.workspace().isPresent())) {
            throw new IllegalStateException(
                    "Workspace dependency root has no selected workspace package for `"
                            + packageId + ":" + version + ":" + scope.lockfileName() + "`.");
        }
    }

    private static DependencyLane lane(WorkspaceProjectEdge edge) {
        return switch (edge.scope()) {
            case "compile" -> edge.exported() ? DependencyLane.API : DependencyLane.IMPLEMENTATION;
            case "test" -> DependencyLane.TEST;
            case "processor" -> DependencyLane.PROCESSOR;
            case "test-processor" -> DependencyLane.TEST_PROCESSOR;
            default -> throw new IllegalStateException(
                    "Unsupported workspace dependency scope `" + edge.scope() + "`.");
        };
    }

    private static DependencyScope scope(String value) {
        return switch (value) {
            case "compile" -> DependencyScope.COMPILE;
            case "test" -> DependencyScope.TEST;
            case "processor" -> DependencyScope.PROCESSOR;
            case "test-processor" -> DependencyScope.TEST_PROCESSOR;
            default -> throw new IllegalStateException(
                    "Unsupported workspace dependency scope `" + value + "`.");
        };
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }
}
