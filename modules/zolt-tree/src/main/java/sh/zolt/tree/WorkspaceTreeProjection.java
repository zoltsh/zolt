package sh.zolt.tree;

import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The read-only workspace projection of a root {@code zolt.lock} shared by the workspace tree views.
 * It is a pure re-reading of committed facts: no resolution, no filesystem, no network.
 *
 * <p><strong>Occurrence identity.</strong> One projected package per lock entry, which is exactly the
 * lock's edge identity {@code (packageId, version, variant, scope)}. Scopes and variants are never
 * merged, so a coordinate present in both {@code compile} and {@code test} stays two occurrences the
 * way the lock records it. The lock may not list one such identity twice, and may not attribute a
 * package to a path the workspace does not declare as a member; both fail closed here rather than
 * emitting a projection a consumer cannot key.
 *
 * <p><strong>Edges.</strong> Every edge is resolved through
 * {@link LockDependencyIndex#resolveGraphEdge} and then re-encoded from the occurrence it resolved to,
 * so a projected edge always spells the full {@code groupId:artifactId:version:variant:scope} identity
 * of a listed package — never the historical scope-less form a lock may still record. A projection that
 * builds successfully cannot contain an edge that fails to name one of its own packages.
 *
 * <p><strong>Child sourcing.</strong> A member-qualified lock records a per-member graph view of a
 * collapsed package identity. The workspace-level child set is the union over the member contexts that
 * consume the occurrence, sourced exactly the way {@code sh.zolt.sbom.WorkspaceSbomAssembler} sources
 * its own graph, so the tree and the SBOM describe the same relationships for one lock.
 */
final class WorkspaceTreeProjection {
    static final String REGENERATE_COMMAND = "zolt resolve --workspace";

    private final LockDependencyIndex index;
    private final LockMemberGraphIndex memberGraphs;
    private final List<LockPackage> packages;
    private final DependencyRootProjection authoredRoots;

    private WorkspaceTreeProjection(
            LockDependencyIndex index,
            LockMemberGraphIndex memberGraphs,
            List<LockPackage> packages,
            DependencyRootProjection authoredRoots) {
        this.index = index;
        this.memberGraphs = memberGraphs;
        this.packages = packages;
        this.authoredRoots = authoredRoots;
    }

    static WorkspaceTreeProjection of(ZoltLockfile lockfile, List<String> memberPaths) {
        requireDistinctOccurrences(lockfile);
        requireDeclaredMembers(lockfile, memberPaths);
        LockDependencyIndex index = new LockDependencyIndex(lockfile.packages());
        LockMemberGraphIndex memberGraphs =
                new LockMemberGraphIndex(lockfile.memberGraphs(), lockfile.packages());
        lockfile.packages().stream()
                .flatMap(lockPackage -> lockPackage.dependencies().stream())
                .forEach(edge -> index.resolveGraphEdge(edge, REGENERATE_COMMAND));
        lockfile.memberGraphs().stream()
                .flatMap(graph -> graph.dependencies().stream())
                .forEach(edge -> index.resolveGraphEdge(edge, REGENERATE_COMMAND));
        List<LockPackage> packages = lockfile.packages().stream()
                .sorted(Comparator
                        .comparing(DependencyTreeLines::coordinate)
                        .thenComparing(lockPackage -> lockPackage.scope().lockfileName()))
                .toList();
        DependencyRootProjection authoredRoots =
                DependencyRootProjection.workspace(lockfile, memberPaths);
        return new WorkspaceTreeProjection(index, memberGraphs, packages, authoredRoots);
    }

    LockDependencyIndex index() {
        return index;
    }

    /** Every locked occurrence, ordered by coordinate then scope so the ordering is total. */
    List<LockPackage> packages() {
        return packages;
    }

    /** The members whose graphs consume this occurrence, sorted and deduplicated. */
    List<String> members(LockPackage lockPackage) {
        return List.copyOf(new TreeSet<>(lockPackage.members()));
    }

    /**
     * The workspace-level child edges of an occurrence: the union across its member contexts, each in
     * the canonical spelling that names the child occurrence exactly.
     */
    List<String> dependencies(LockPackage lockPackage) {
        TreeSet<String> edges = new TreeSet<>();
        for (String edge : recordedEdges(lockPackage)) {
            edges.add(canonical(edge));
        }
        return List.copyOf(edges);
    }

    /** Materialized graph roots across all members, as legacy schema display coordinates. */
    List<String> roots() {
        return authoredRoots.graphRootCoordinates();
    }

    /** The authored roots owned by one member, in canonical lane and coordinate order. */
    List<DependencyRootProjection.Root> rootsFor(String member) {
        return authoredRoots.rootsFor(member);
    }

    boolean direct(LockPackage lockPackage) {
        return authoredRoots.selects(lockPackage);
    }

    /** One member's own graph and policy view of the collapsed lock entries. */
    DependencyTreeLines.View viewFor(String member) {
        return new DependencyTreeLines.View() {
            @Override
            public List<String> dependencies(LockPackage lockPackage) {
                return memberGraphs.dependenciesFor(member, lockPackage);
            }

            @Override
            public List<String> policies(LockPackage lockPackage) {
                return memberGraphs.policiesFor(member, lockPackage);
            }
        };
    }

    /**
     * The raw child edges the lock records for an occurrence, sourced exactly as
     * {@code WorkspaceSbomAssembler} sources them: a first-party entry uses its collapsed list, an
     * unattributed entry uses its collapsed list, and every attributed entry unions its per-member
     * graph views (each of which falls back to the collapsed list when the lock records no graph for
     * that member). Any looser sourcing would make the tree and the SBOM disagree about the same lock.
     */
    private Set<String> recordedEdges(LockPackage lockPackage) {
        if (lockPackage.workspace().isPresent() || lockPackage.members().isEmpty()) {
            return new LinkedHashSet<>(lockPackage.dependencies());
        }
        Set<String> edges = new LinkedHashSet<>();
        for (String member : lockPackage.members()) {
            edges.addAll(memberGraphs.dependenciesFor(member, lockPackage));
        }
        return edges;
    }

    /**
     * The canonical spelling of one recorded edge: the full identity of the occurrence it resolves to.
     * A lock may still record a legacy bare-GAV or variant-only edge; emitting that string verbatim
     * would hand a consumer an edge matching no listed occurrence.
     */
    private String canonical(String edge) {
        return index.resolveGraphEdge(edge, REGENERATE_COMMAND)
                .map(target -> LockDependencyEdge.of(target).encode())
                .orElseThrow();
    }

    private static void requireDistinctOccurrences(ZoltLockfile lockfile) {
        Set<String> seen = new LinkedHashSet<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            String identity = LockDependencyEdge.of(lockPackage).encode();
            if (!seen.add(identity)) {
                throw new LockDependencyGraphException(
                        "Workspace zolt.lock lists the package occurrence `"
                                + identity
                                + "` more than once, so its projected packages cannot be told apart. Run `"
                                + REGENERATE_COMMAND
                                + "` to regenerate zolt.lock with one entry per package, version, variant,"
                                + " and scope.");
            }
        }
    }

    private static void requireDeclaredMembers(ZoltLockfile lockfile, List<String> memberPaths) {
        Set<String> declared = Set.copyOf(memberPaths);
        Set<String> undeclared = new TreeSet<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            lockPackage.members().stream()
                    .filter(member -> !declared.contains(member))
                    .forEach(undeclared::add);
        }
        if (!undeclared.isEmpty()) {
            throw new LockDependencyGraphException(
                    "Workspace zolt.lock attributes packages to "
                            + undeclared
                            + ", which the workspace does not declare as members. Run `"
                            + REGENERATE_COMMAND
                            + "` to regenerate zolt.lock against the current workspace configuration.");
        }
    }
}
