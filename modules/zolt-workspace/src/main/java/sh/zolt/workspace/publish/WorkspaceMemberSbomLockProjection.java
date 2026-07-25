package sh.zolt.workspace.publish;

import static sh.zolt.workspace.publish.MemberDependencyVariants.ref;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects the aggregated workspace lock into an <em>SBOM-shaped</em> lockfile for one member, so
 * {@link WorkspaceMemberSbomGenerator} (running the single-project {@code LockSbomAssembler}) can emit
 * that member's COMPLETE CycloneDX graph — transitive components, artifact hashes, and dependency
 * edges intact.
 *
 * <p>Contrast {@link WorkspaceMemberPomLockProjection}, which is deliberately POM-shaped: it lists a
 * member's declared directs only, reconstructed with empty jar/pom hashes and empty edge lists. That
 * is correct for a POM (a POM declares only directs) but materially understates an SBOM, which is
 * supply-chain evidence: it must carry every reachable transitive component, each component's SHA-256,
 * and the external&#8594;external edges. The two projections coexist — the POM one feeds POM
 * generation, this one feeds SBOM generation.
 *
 * <p><strong>Closure.</strong> Starting from the member's direct coordinates (resolved against the
 * aggregated lock) and its workspace siblings, it walks {@link LockPackage#dependencies()} edges
 * breadth-first and retains every reached package <em>as-is</em> — preserving artifact paths,
 * jar/pom/artifact SHA-256 hashes, source repositories, scopes, policies, and the edge lists. Nothing
 * is reconstructed; scope filtering (dev/test/provided excluded by default) is left to the assembler's
 * {@code SbomScopeSelection}, which reads the carried-through scopes.
 *
 * <p><strong>Workspace siblings (fact: a sibling's own externals reach the consumer).</strong> The
 * aggregated lock attributes a sibling-owned external (guava, declared by acme-core) onto the consumer
 * (acme-http) classpath, yet a workspace lock entry deliberately carries an EMPTY
 * {@link LockPackage#dependencies()} — aggregation cannot attribute a sibling's own edges — so a plain
 * BFS would stop at the sibling and drop guava from acme-http's SBOM. This projection is therefore
 * workspace-aware: for every sibling reachable from the member it resolves the sibling's own
 * policy-merged {@link ProjectConfig} (via {@link WorkspaceMemberPolicyResolver}, exactly as
 * {@code WorkspacePublishService} does) and materializes a populated copy of the sibling's lock entry
 * whose {@code dependencies} list carries synthetic edges to the sibling's <em>propagating</em> direct
 * externals — its api/compile/runtime dependencies, the scopes that transitively land on a consumer's
 * classpath ({@code provided}/{@code dev}/{@code test} are NOT transitive and are excluded). Its own
 * workspace siblings recurse the same way, so a transitive sibling chain (http&#8594;core&#8594;util)
 * fully resolves. The unified BFS then walks those synthetic edges and each external's own edges,
 * pulling the sibling-owned transitive externals into the member's SBOM as-is.
 *
 * <p><strong>Directness (fact 6).</strong> The aggregated lock's {@code direct} flag is OR'd across
 * every member and must NOT drive a member's SBOM. {@code LockSbomAssembler} turns each
 * {@code direct()} package into a {@code root -> package} edge, so every closure package's flag is
 * re-stamped to this member's view: {@code true} only for a coordinate the member ITSELF declares
 * direct; sibling-owned externals and transitively-reached siblings are {@code false}.
 */
public final class WorkspaceMemberSbomLockProjection {
    /**
     * @param memberConfig the member's (policy-merged) effective config — the sole source of directness
     * @param aggregatedLock the workspace root lock — the source of resolved versions, hashes, and edges
     * @param workspace the enclosing workspace — supplies the sibling members whose configs are recursed
     * @param policyResolver merges each sibling's effective config, exactly as the publish flow resolves it
     * @return an SBOM-shaped lockfile: the member's full reachable closure, carried through as-is
     */
    public ZoltLockfile project(
            String memberPath,
            ProjectConfig memberConfig,
            ZoltLockfile aggregatedLock,
            Workspace workspace,
            WorkspaceMemberPolicyResolver policyResolver) {
        Map<String, LockPackage> byRef = new LinkedHashMap<>();
        MemberDependencyVariants.ExternalIndex externalIndex = new MemberDependencyVariants.ExternalIndex();
        Map<String, List<LockPackage>> externalCandidates = new LinkedHashMap<>();
        Map<String, LockPackage> workspaceByCoordinate = new LinkedHashMap<>();
        for (LockPackage lockPackage : aggregatedLock.packages()) {
            byRef.putIfAbsent(ref(lockPackage), lockPackage);
            String coordinate = coordinate(lockPackage);
            if (lockPackage.workspace().isPresent()) {
                workspaceByCoordinate.putIfAbsent(coordinate, lockPackage);
            } else {
                externalIndex.add(coordinate, lockPackage);
                externalCandidates.computeIfAbsent(coordinate, key -> new ArrayList<>()).add(lockPackage);
            }
        }

        // Resolve the member's transitive workspace-sibling closure and synthesize each sibling's edges.
        // A workspace lock entry carries no edges, so this materializes a populated copy per sibling whose
        // dependencies point at its propagating direct externals (and its own workspace siblings). Both
        // root resolution and the BFS below then see those edges through the overlay.
        WorkspaceMemberSiblingClosure closure = new WorkspaceMemberSiblingClosure(
                workspace, policyResolver, workspaceByCoordinate, externalCandidates);
        Map<String, LockPackage> populatedSiblings = closure.populate(directWorkspaceCoordinates(memberConfig));
        for (Map.Entry<String, LockPackage> entry : populatedSiblings.entrySet()) {
            byRef.put(ref(entry.getValue()), entry.getValue());
            workspaceByCoordinate.put(entry.getKey(), entry.getValue());
        }

        // The member's authoritative direct set (by variant-qualified ref) and the closure's BFS roots,
        // ordered exactly like the POM projection: api, workspace-api, compile, workspace, runtime,
        // provided. External roots resolve to the member's OWN variant (its declared classifier/type), so a
        // member's SBOM roots at its osx-classified netty, never a sibling's linux variant at the same GA.
        Set<String> directRefs = new LinkedHashSet<>();
        Deque<LockPackage> roots = new ArrayDeque<>();
        addWorkspaceRoots(roots, directRefs, memberConfig.workspaceApiDependencies().keySet(), workspaceByCoordinate);
        addExternalRoots(roots, directRefs, memberConfig.apiDependencies().keySet(), DependencyScope.COMPILE,
                memberPath, memberConfig, externalIndex);
        addExternalRoots(roots, directRefs, memberConfig.managedApiDependencies(), DependencyScope.COMPILE,
                memberPath, memberConfig, externalIndex);
        addWorkspaceRoots(roots, directRefs, memberConfig.workspaceDependencies().keySet(), workspaceByCoordinate);
        addExternalRoots(roots, directRefs, memberConfig.dependencies().keySet(), DependencyScope.COMPILE,
                memberPath, memberConfig, externalIndex);
        addExternalRoots(roots, directRefs, memberConfig.managedDependencies(), DependencyScope.COMPILE,
                memberPath, memberConfig, externalIndex);
        addExternalRoots(roots, directRefs, memberConfig.runtimeDependencies().keySet(), DependencyScope.RUNTIME,
                memberPath, memberConfig, externalIndex);
        addExternalRoots(roots, directRefs, memberConfig.managedRuntimeDependencies(), DependencyScope.RUNTIME,
                memberPath, memberConfig, externalIndex);
        addExternalRoots(roots, directRefs, memberConfig.providedDependencies().keySet(), DependencyScope.PROVIDED,
                memberPath, memberConfig, externalIndex);
        addExternalRoots(roots, directRefs, memberConfig.managedProvidedDependencies(), DependencyScope.PROVIDED,
                memberPath, memberConfig, externalIndex);

        // Breadth-first over the aggregated lock's variant-qualified dependency edges, retaining each
        // reached package as-is. A variant-qualified edge resolves to its exact variant; a bare edge to the
        // default/sole one. Insertion-ordered so the projected lock is deterministic.
        LockDependencyIndex edges = new LockDependencyIndex(byRef.values());
        LockMemberGraphIndex memberGraphs = new LockMemberGraphIndex(aggregatedLock.memberGraphs());
        Map<String, LockPackage> reached = new LinkedHashMap<>();
        Map<String, Set<String>> reachedDependencies = new LinkedHashMap<>();
        Map<String, Set<String>> reachedPolicies = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        Deque<MemberPackage> queue = new ArrayDeque<>();
        roots.forEach(root -> queue.addLast(new MemberPackage(
                root.workspace().orElse(memberPath),
                root)));
        while (!queue.isEmpty()) {
            MemberPackage current = queue.removeFirst();
            String ref = ref(current.lockPackage());
            if (!visited.add(current.member() + "|" + ref)) {
                continue;
            }
            reached.putIfAbsent(ref, current.lockPackage());
            List<String> dependencies =
                    memberGraphs.dependenciesFor(current.member(), current.lockPackage());
            Set<String> retainedDependencies =
                    reachedDependencies.computeIfAbsent(ref, ignored -> new LinkedHashSet<>());
            reachedPolicies
                    .computeIfAbsent(ref, ignored -> new LinkedHashSet<>())
                    .addAll(memberGraphs.policiesFor(current.member(), current.lockPackage()));
            for (String edge : dependencies) {
                LockPackage target = edges.resolveGraphEdge(edge, "zolt resolve --workspace")
                        .orElseThrow();
                boolean traversingSibling = !current.member().equals(memberPath);
                if (traversingSibling && memberGraphs.optionalFor(current.member(), target)) {
                    continue;
                }
                retainedDependencies.add(edge);
                MemberPackage next = new MemberPackage(
                        target.workspace().orElse(current.member()),
                        target);
                if (!visited.contains(next.member() + "|" + ref(next.lockPackage()))) {
                    queue.addLast(next);
                }
            }
        }

        List<LockPackage> projected = new ArrayList<>(reached.size());
        for (LockPackage lockPackage : reached.values()) {
            String ref = ref(lockPackage);
            projected.add(withMemberView(
                    lockPackage,
                    directRefs.contains(ref),
                    List.copyOf(reachedDependencies.getOrDefault(ref, Set.of())),
                    List.copyOf(reachedPolicies.getOrDefault(ref, Set.of()))));
        }
        return new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.copyOf(projected), List.of());
    }

    /** The member's own direct workspace-dependency coordinates: the seed for the sibling closure. */
    private static Set<String> directWorkspaceCoordinates(ProjectConfig config) {
        Set<String> coordinates = new LinkedHashSet<>();
        coordinates.addAll(config.workspaceApiDependencies().keySet());
        coordinates.addAll(config.workspaceDependencies().keySet());
        return coordinates;
    }

    private static void addWorkspaceRoots(
            Deque<LockPackage> roots,
            Set<String> directRefs,
            Set<String> coordinates,
            Map<String, LockPackage> workspaceByCoordinate) {
        for (String coordinate : coordinates) {
            LockPackage resolved = workspaceByCoordinate.get(coordinate);
            if (resolved != null && directRefs.add(ref(resolved))) {
                roots.addLast(resolved);
            }
        }
    }

    private static void addExternalRoots(
            Deque<LockPackage> roots,
            Set<String> directRefs,
            Set<String> coordinates,
            DependencyScope scope,
            String memberPath,
            ProjectConfig memberConfig,
            MemberDependencyVariants.ExternalIndex externalIndex) {
        for (String coordinate : coordinates) {
            LockPackage resolved = externalIndex.resolve(
                    coordinate,
                    MemberDependencyVariants.declaredVariant(memberConfig, coordinate, scope),
                    scope,
                    memberPath);
            if (resolved != null && directRefs.add(ref(resolved))) {
                roots.addLast(resolved);
            }
        }
    }

    /** Re-stamps aggregate facts to the member-qualified graph view used by this projected SBOM. */
    private static LockPackage withMemberView(
            LockPackage lockPackage,
            boolean direct,
            List<String> dependencies,
            List<String> policies) {
        if (lockPackage.direct() == direct
                && lockPackage.dependencies().equals(dependencies)
                && lockPackage.policies().equals(policies)) {
            return lockPackage;
        }
        return new LockPackage(
                lockPackage.packageId(),
                lockPackage.version(),
                lockPackage.source(),
                lockPackage.scope(),
                direct,
                lockPackage.jar(),
                lockPackage.pom(),
                lockPackage.jarSha256(),
                lockPackage.pomSha256(),
                lockPackage.artifact(),
                lockPackage.artifactType(),
                lockPackage.artifactSha256(),
                lockPackage.workspace(),
                lockPackage.workspaceOutput(),
                dependencies.stream().sorted().toList(),
                lockPackage.members(),
                lockPackage.exportedBy(),
                policies.stream().sorted().toList(),
                lockPackage.toolGroups());
    }

    private static String coordinate(LockPackage lockPackage) {
        return lockPackage.packageId().groupId() + ":" + lockPackage.packageId().artifactId();
    }

    private record MemberPackage(String member, LockPackage lockPackage) {
    }
}
