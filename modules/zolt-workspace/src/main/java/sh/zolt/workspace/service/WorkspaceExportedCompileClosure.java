package sh.zolt.workspace.service;

import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Walks the member-qualified graph from API packages exported by visible workspace dependencies.
 */
final class WorkspaceExportedCompileClosure {
    private WorkspaceExportedCompileClosure() {
    }

    static Set<String> compute(
            ZoltLockfile lockfile,
            Set<String> dependencyClosure,
            WorkspaceLockIndex lockIndex) {
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
                    lockIndex.memberGraphs().dependenciesFor(
                            current.member(),
                            current.lockPackage())) {
                lockIndex.dependencies().resolveGraphEdge(dependency, "zolt resolve --workspace")
                        .filter(candidate -> candidate.scope().entersMainCompileClasspath())
                        .filter(candidate -> !lockIndex.memberGraphs().optionalOnlyFor(
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
