package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Walks the member-qualified graph from API packages exported by visible workspace dependencies.
 *
 * <p>The walk never crosses members: a queue entry carries the member it was seeded from, and every
 * edge it follows is that member's own graph row. The closure over a set of members is therefore
 * exactly the union of the per-member closures, which is what lets one member's contribution be
 * computed once and reused by every dependent that can see it.
 */
final class WorkspaceExportedCompileClosure {
    private WorkspaceExportedCompileClosure() {
    }

    static Set<String> compute(
            ZoltLockfile lockfile,
            Set<String> dependencyClosure,
            LockDependencyIndex dependencies,
            LockMemberGraphIndex memberGraphs) {
        Set<String> reached = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<MemberPackage> queue = new ArrayDeque<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            if (lockPackage.workspace().isEmpty()
                    && lockPackage.scope().entersMainCompileClasspath()) {
                lockPackage.exportedBy().stream()
                        .filter(dependencyClosure::contains)
                        .forEach(member -> queue.addLast(new MemberPackage(member, lockPackage)));
            }
        }
        while (!queue.isEmpty()) {
            MemberPackage current = queue.removeFirst();
            String currentRef = ref(current.lockPackage());
            if (!visited.add(current.member() + "|" + currentRef)) {
                continue;
            }
            reached.add(currentRef);
            for (String dependency :
                    memberGraphs.dependenciesFor(current.member(), current.lockPackage())) {
                dependencies.resolveGraphEdge(dependency, "zolt resolve --workspace")
                        .filter(candidate -> candidate.scope().entersMainCompileClasspath())
                        .filter(candidate -> !memberGraphs.optionalOnlyFor(
                                current.member(), candidate))
                        .map(candidate -> new MemberPackage(current.member(), candidate))
                        .filter(candidate -> !visited.contains(
                                candidate.member() + "|" + ref(candidate.lockPackage())))
                        .ifPresent(queue::addLast);
            }
        }
        return reached;
    }

    static String ref(LockPackage lockPackage) {
        return LockDependencyEdge.of(lockPackage).encode();
    }

    private record MemberPackage(String member, LockPackage lockPackage) {
    }
}
