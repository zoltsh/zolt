package sh.zolt.workspace.publish;

import static sh.zolt.workspace.publish.MemberDependencyVariants.ref;

import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockGraphRootSelector;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishException;
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
 * <p><strong>Closure.</strong> Starting from the member's exact non-publish-only v7 dependency roots
 * and its workspace siblings, it walks {@link LockPackage#dependencies()} edges
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
 * dependency roots and materializes a populated copy of the sibling's lock entry
 * whose {@code dependencies} list carries synthetic edges to the sibling's <em>propagating</em> direct
 * externals — its api/compile/runtime dependencies, the scopes that transitively land on a consumer's
 * classpath ({@code provided}/{@code dev}/{@code test} are NOT transitive and are excluded). Its own
 * workspace siblings recurse the same way, so a transitive sibling chain (http&#8594;core&#8594;util)
 * fully resolves. The unified BFS then walks those synthetic edges and each external's own edges,
 * pulling the sibling-owned transitive externals into the member's SBOM as-is.
 *
 * <p><strong>Root authority.</strong> The aggregated lock's {@code direct} flag is OR'd across every
 * member and must NOT drive a member's SBOM. Member-qualified {@code dependencyRoot} records select
 * the exact version, variant, and resolved scope. The projected lock preserves those member-qualified
 * roots and package attribution; {@code direct} is retained only as a compatible derived view.
 */
public final class WorkspaceMemberSbomLockProjection {
    /**
     * @param memberConfig retained for the established publication call shape; lock v7 is root authority
     * @param aggregatedLock the workspace root lock — the source of resolved versions, hashes, and edges
     * @param workspace the enclosing workspace — supplies the sibling members whose configs are recursed
     * @param policyResolver retained for the established publication call shape
     * @return an SBOM-shaped lockfile: the member's full reachable closure, carried through as-is
     */
    public ZoltLockfile project(
            String memberPath,
            ProjectConfig memberConfig,
            ZoltLockfile aggregatedLock,
            Workspace workspace,
            WorkspaceMemberPolicyResolver policyResolver) {
        if (aggregatedLock.version() != ZoltLockfile.CURRENT_VERSION) {
            throw new PublishException(
                    "Workspace SBOM generation requires zolt.lock version " + ZoltLockfile.CURRENT_VERSION
                            + ", but found version " + aggregatedLock.version()
                            + ". Run `zolt resolve --workspace` to regenerate the lockfile.");
        }
        Map<String, LockPackage> byRef = new LinkedHashMap<>();
        Map<String, LockPackage> workspaceByRef = new LinkedHashMap<>();
        for (LockPackage lockPackage : aggregatedLock.packages()) {
            byRef.putIfAbsent(ref(lockPackage), lockPackage);
            if (lockPackage.workspace().isPresent()) {
                workspaceByRef.putIfAbsent(ref(lockPackage), lockPackage);
            }
        }

        List<LockDependencyRoot> memberRoots = aggregatedLock.dependencyRoots().stream()
                .filter(root -> root.member().equals(memberPath))
                .filter(root -> !root.publishOnly())
                .toList();
        LockMemberGraphIndex memberGraphs = new LockMemberGraphIndex(aggregatedLock.memberGraphs());
        List<LockPackage> memberPackages = aggregatedLock.packages().stream()
                .filter(lockPackage -> lockPackage.members().contains(memberPath))
                .map(lockPackage -> withMemberView(
                        lockPackage,
                        lockPackage.direct(),
                        memberGraphs.dependenciesFor(memberPath, lockPackage),
                        memberGraphs.policiesFor(memberPath, lockPackage)))
                .toList();
        memberPackages.forEach(lockPackage -> byRef.put(ref(lockPackage), lockPackage));
        List<LockPackage> rootPackages = memberRoots.stream()
                .map(root -> memberPackages.stream()
                        .filter(root::selects)
                        .findFirst()
                        .orElseThrow())
                .toList();
        List<LockPackage> closureRoots = LockGraphRootSelector.select(
                memberPackages,
                memberRoots,
                "zolt resolve --workspace");

        // Resolve the member's transitive workspace-sibling closure and synthesize each sibling's edges.
        // A workspace lock entry carries no edges, so this materializes a populated copy per sibling whose
        // dependencies point at its propagating direct externals (and its own workspace siblings). Both
        // root resolution and the BFS below then see those edges through the overlay.
        WorkspaceMemberSiblingClosure closure = new WorkspaceMemberSiblingClosure(
                workspace,
                workspaceByRef,
                aggregatedLock.packages(),
                aggregatedLock.dependencyRoots());
        Set<String> workspaceRootRefs = closureRoots.stream()
                .filter(lockPackage -> lockPackage.workspace().isPresent())
                .map(MemberDependencyVariants::ref)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, LockPackage> populatedSiblings = closure.populate(workspaceRootRefs);
        for (Map.Entry<String, LockPackage> entry : populatedSiblings.entrySet()) {
            byRef.put(entry.getKey(), entry.getValue());
        }

        Set<String> directRefs = rootPackages.stream()
                .map(MemberDependencyVariants::ref)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Deque<LockPackage> roots = new ArrayDeque<>();
        closureRoots.stream()
                .map(lockPackage -> byRef.getOrDefault(ref(lockPackage), lockPackage))
                .forEach(roots::addLast);

        // Breadth-first over the aggregated lock's variant-qualified dependency edges, retaining each
        // reached package as-is. A variant-qualified edge resolves to its exact variant; a bare edge to the
        // default/sole one. Insertion-ordered so the projected lock is deterministic.
        LockDependencyIndex edges = new LockDependencyIndex(byRef.values());
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
                if (traversingSibling && memberGraphs.optionalOnlyFor(current.member(), target)) {
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
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                List.of(),
                List.copyOf(projected),
                List.of(),
                List.of(),
                List.of(),
                memberRoots);
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

    private record MemberPackage(String member, LockPackage lockPackage) {
    }
}
