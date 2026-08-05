package sh.zolt.tree;

import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * The read-only workspace projection of a root {@code zolt.lock} shared by the workspace tree views.
 * It is a pure re-reading of committed facts: no resolution, no filesystem, no network.
 *
 * <p><strong>Occurrence identity.</strong> One projected package per lock entry, which is exactly the
 * lock's edge identity {@code (packageId, version, variant, scope)}. Scopes and variants are never
 * merged, so a coordinate present in both {@code compile} and {@code test} stays two occurrences the
 * way the lock records it.
 *
 * <p><strong>Edges.</strong> A member-qualified lock records a per-member graph view of a collapsed
 * package identity, so the workspace-level child set of an occurrence is the union over the members
 * that consume it (plus the entry's own collapsed list, which is the fallback view). Every edge is
 * resolved through {@link LockDependencyIndex#resolveGraphEdge} up front, so a projection that builds
 * successfully cannot contain an edge that fails to name one of its own packages.
 */
final class WorkspaceTreeProjection {
    static final String REGENERATE_COMMAND = "zolt resolve --workspace";

    private final LockDependencyIndex index;
    private final LockMemberGraphIndex memberGraphs;
    private final List<LockPackage> packages;

    private WorkspaceTreeProjection(
            LockDependencyIndex index,
            LockMemberGraphIndex memberGraphs,
            List<LockPackage> packages) {
        this.index = index;
        this.memberGraphs = memberGraphs;
        this.packages = packages;
    }

    static WorkspaceTreeProjection of(ZoltLockfile lockfile) {
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
        return new WorkspaceTreeProjection(index, memberGraphs, packages);
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

    /** The workspace-level child edges of an occurrence: the union across the members that use it. */
    List<String> dependencies(LockPackage lockPackage) {
        TreeSet<String> edges = new TreeSet<>(lockPackage.dependencies());
        for (String member : lockPackage.members()) {
            edges.addAll(memberGraphs.dependenciesFor(member, lockPackage));
        }
        return List.copyOf(edges);
    }

    /** The union of every member's direct declarations, as display coordinates. */
    List<String> roots() {
        TreeSet<String> roots = new TreeSet<>();
        packages.stream()
                .filter(LockPackage::direct)
                .map(DependencyTreeLines::coordinate)
                .forEach(roots::add);
        return List.copyOf(roots);
    }

    /** The occurrences one member declares directly, in the standalone view's ordering. */
    List<LockPackage> directPackagesFor(String member) {
        return packages.stream()
                .filter(LockPackage::direct)
                .filter(lockPackage -> lockPackage.members().contains(member))
                .toList();
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
}
