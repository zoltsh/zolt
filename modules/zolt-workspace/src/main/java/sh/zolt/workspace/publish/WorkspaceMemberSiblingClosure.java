package sh.zolt.workspace.publish;

import static sh.zolt.workspace.publish.MemberDependencyVariants.ref;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a member's transitive workspace-sibling closure, materializing per-sibling lock copies whose
 * {@code dependencies} carry synthetic edges the aggregated lock cannot (a workspace lock entry has no
 * edges). Each sibling contributes variant-qualified edges to its api/compile/runtime externals (the
 * scopes that transitively reach a consumer's classpath) and to its own workspace siblings, which are
 * recursed the same way. Feeds {@link WorkspaceMemberSbomLockProjection}'s closure BFS.
 */
final class WorkspaceMemberSiblingClosure {
    private final Map<String, LockPackage> workspaceByRef;
    private final List<LockPackage> packages;
    private final List<LockDependencyRoot> roots;
    private final Map<String, WorkspaceMember> membersByPath = new LinkedHashMap<>();
    private final Map<String, LockPackage> populated = new LinkedHashMap<>();
    private final Set<String> visited = new LinkedHashSet<>();

    WorkspaceMemberSiblingClosure(
            Workspace workspace,
            Map<String, LockPackage> workspaceByRef,
            List<LockPackage> packages,
            List<LockDependencyRoot> roots) {
        this.workspaceByRef = workspaceByRef;
        this.packages = packages;
        this.roots = roots;
        for (WorkspaceMember member : workspace.members()) {
            membersByPath.putIfAbsent(member.path(), member);
        }
    }

    /**
     * Populates every sibling occurrence reachable from {@code seedRefs}, returning an exact-ref&#8594;copy
     * map whose values carry the synthesized dependency edges. Siblings with no propagating externals
     * (and no workspace siblings) come back byte-identical to their aggregated entry.
     */
    Map<String, LockPackage> populate(Set<String> seedRefs) {
        Deque<String> queue = new ArrayDeque<>(seedRefs);
        while (!queue.isEmpty()) {
            String siblingRef = queue.removeFirst();
            if (!visited.add(siblingRef)) {
                continue;
            }
            LockPackage siblingPackage = workspaceByRef.get(siblingRef);
            WorkspaceMember member = siblingPackage == null
                    ? null
                    : siblingPackage.workspace().map(membersByPath::get).orElse(null);
            if (siblingPackage == null || member == null) {
                // Sibling absent from the lock, or not a known member: it is still carried as a bare
                // first-party component by the BFS, matching the prior sibling-only behavior.
                continue;
            }
            List<String> edges = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            roots.stream()
                    .filter(root -> root.member().equals(member.path()))
                    .filter(root -> !root.publishOnly() && !root.optional())
                    .filter(root -> propagates(root.lane()))
                    .map(root -> packages.stream().filter(root::selects).findFirst().orElseThrow())
                    .forEach(resolved -> {
                        if (seen.add(ref(resolved))) {
                            edges.add(ref(resolved));
                        }
                        if (resolved.workspace().isPresent()) {
                            queue.addLast(ref(resolved));
                        }
                    });
            populated.put(siblingRef, withDependencies(siblingPackage, List.copyOf(edges)));
        }
        return populated;
    }

    private static boolean propagates(DependencyLane lane) {
        return switch (lane) {
            case API, IMPLEMENTATION, RUNTIME -> true;
            case PROVIDED, DEV, TEST, PROCESSOR, TEST_PROCESSOR -> false;
        };
    }

    /** Carries a workspace-sibling package through unchanged except for a populated {@code dependencies} list. */
    private static LockPackage withDependencies(LockPackage lockPackage, List<String> dependencies) {
        if (lockPackage.dependencies().equals(dependencies)) {
            return lockPackage;
        }
        return new LockPackage(
                lockPackage.packageId(),
                lockPackage.version(),
                lockPackage.source(),
                lockPackage.scope(),
                lockPackage.direct(),
                lockPackage.jar(),
                lockPackage.pom(),
                lockPackage.jarSha256(),
                lockPackage.pomSha256(),
                lockPackage.artifact(),
                lockPackage.artifactType(),
                lockPackage.artifactSha256(),
                lockPackage.workspace(),
                lockPackage.workspaceOutput(),
                dependencies,
                lockPackage.members(),
                lockPackage.exportedBy(),
                lockPackage.policies(),
                lockPackage.toolGroups());
    }
}
