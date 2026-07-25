package sh.zolt.workspace.publish;

import static sh.zolt.workspace.publish.MemberDependencyVariants.ref;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishException;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
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
    private final Workspace workspace;
    private final WorkspaceMemberPolicyResolver policyResolver;
    private final Map<String, LockPackage> workspaceByCoordinate;
    private final Map<String, List<LockPackage>> externalCandidates;
    private final Map<String, WorkspaceMember> membersByCoordinate = new LinkedHashMap<>();
    private final Map<String, ProjectConfig> effectiveConfigs = new LinkedHashMap<>();
    private final Map<String, LockPackage> populated = new LinkedHashMap<>();
    private final Set<String> visited = new LinkedHashSet<>();

    WorkspaceMemberSiblingClosure(
            Workspace workspace,
            WorkspaceMemberPolicyResolver policyResolver,
            Map<String, LockPackage> workspaceByCoordinate,
            Map<String, List<LockPackage>> externalCandidates) {
        this.workspace = workspace;
        this.policyResolver = policyResolver;
        this.workspaceByCoordinate = workspaceByCoordinate;
        this.externalCandidates = externalCandidates;
        for (WorkspaceMember member : workspace.members()) {
            membersByCoordinate.putIfAbsent(memberCoordinate(member.config()), member);
        }
    }

    /**
     * Populates every sibling reachable from {@code seedCoordinates}, returning a coordinate&#8594;copy
     * map whose values carry the synthesized dependency edges. Siblings with no propagating externals
     * (and no workspace siblings) come back byte-identical to their aggregated entry.
     */
    Map<String, LockPackage> populate(Set<String> seedCoordinates) {
        Deque<String> queue = new ArrayDeque<>(seedCoordinates);
        while (!queue.isEmpty()) {
            String coordinate = queue.removeFirst();
            if (!visited.add(coordinate)) {
                continue;
            }
            LockPackage siblingPackage = workspaceByCoordinate.get(coordinate);
            WorkspaceMember member = membersByCoordinate.get(coordinate);
            if (siblingPackage == null || member == null) {
                // Sibling absent from the lock, or not a known member: it is still carried as a bare
                // first-party component by the BFS, matching the prior sibling-only behavior.
                continue;
            }
            ProjectConfig config = effectiveConfig(member);
            List<String> edges = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            // Propagating externals only: api + compile + runtime. provided/dev/test are NOT
            // transitive, so a sibling's provided/test externals never reach the consumer's SBOM.
            addExternalEdges(
                    edges, seen, member.path(), config, DependencyScope.COMPILE, config.apiDependencies().keySet());
            addExternalEdges(
                    edges, seen, member.path(), config, DependencyScope.COMPILE, config.managedApiDependencies());
            addExternalEdges(
                    edges, seen, member.path(), config, DependencyScope.COMPILE, config.dependencies().keySet());
            addExternalEdges(
                    edges, seen, member.path(), config, DependencyScope.COMPILE, config.managedDependencies());
            addExternalEdges(
                    edges, seen, member.path(), config, DependencyScope.RUNTIME, config.runtimeDependencies().keySet());
            addExternalEdges(
                    edges, seen, member.path(), config, DependencyScope.RUNTIME, config.managedRuntimeDependencies());
            // Siblings-of-siblings: edge to the workspace sibling, and recurse into it the same way.
            addWorkspaceEdges(
                    edges,
                    seen,
                    queue,
                    config,
                    config.workspaceApiDependencies().keySet());
            addWorkspaceEdges(
                    edges,
                    seen,
                    queue,
                    config,
                    config.workspaceDependencies().keySet());
            populated.put(coordinate, withDependencies(siblingPackage, List.copyOf(edges)));
        }
        return populated;
    }

    private void addExternalEdges(
            List<String> edges,
            Set<String> seen,
            String memberPath,
            ProjectConfig config,
            DependencyScope scope,
            Set<String> coordinates) {
        for (String coordinate : coordinates) {
            if (MemberDependencyVariants.optional(
                    config, coordinate, scope)) {
                continue;
            }
            LockPackage resolved = resolveExternal(
                    coordinate,
                    MemberDependencyVariants.declaredVariant(config, coordinate, scope),
                    scope,
                    memberPath);
            if (resolved != null && seen.add(ref(resolved))) {
                edges.add(ref(resolved));
            }
        }
    }

    private void addWorkspaceEdges(
            List<String> edges,
            Set<String> seen,
            Deque<String> queue,
            ProjectConfig config,
            Set<String> coordinates) {
        for (String coordinate : coordinates) {
            if (MemberDependencyVariants.optional(
                    config, coordinate, DependencyScope.COMPILE)) {
                continue;
            }
            LockPackage resolved = workspaceByCoordinate.get(coordinate);
            if (resolved != null && seen.add(ref(resolved))) {
                edges.add(ref(resolved));
            }
            queue.addLast(coordinate);
        }
    }

    /** Resolves the exact variant declared by the sibling and confirms that entry is attributed to it. */
    private LockPackage resolveExternal(
            String coordinate,
            LockArtifactVariant declaredVariant,
            DependencyScope scope,
            String memberPath) {
        List<LockPackage> candidates = externalCandidates.get(coordinate);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        boolean qualifiedEntryPresent = false;
        for (LockPackage candidate : candidates) {
            if (!LockArtifactVariant.of(candidate).equals(declaredVariant)
                    || candidate.scope() != scope) {
                continue;
            }
            qualifiedEntryPresent = true;
            if (candidate.members().contains(memberPath)) {
                return candidate;
            }
        }
        if (!qualifiedEntryPresent) {
            throw new PublishException(
                    "Workspace zolt.lock does not contain the declared artifact variant `"
                            + coordinate
                            + ":"
                            + declaredVariant.key()
                            + "` in scope `"
                            + scope.lockfileName()
                            + "` for sibling `"
                            + memberPath
                            + "`. Run `zolt resolve --workspace` to regenerate the lock before publishing.");
        }
        throw new PublishException(
                "Workspace zolt.lock contains `"
                        + coordinate
                        + ":"
                        + declaredVariant.key()
                        + "` but does not attribute it to sibling `"
                        + memberPath
                        + "`. Run `zolt resolve --workspace` to regenerate the lock before publishing.");
    }

    private ProjectConfig effectiveConfig(WorkspaceMember member) {
        return effectiveConfigs.computeIfAbsent(
                member.path(), key -> policyResolver.merge(workspace, member));
    }

    private static String memberCoordinate(ProjectConfig config) {
        return config.project().group() + ":" + config.project().name();
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
