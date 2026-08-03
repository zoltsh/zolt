package sh.zolt.workspace.service;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Command-scoped indexes over immutable lockfile facts.
 */
final class WorkspaceLockIndex {
    private final LockDependencyIndex dependencies;
    private final LockMemberGraphIndex memberGraphs;

    WorkspaceLockIndex(ZoltLockfile lockfile) {
        dependencies = new LockDependencyIndex(lockfile.packages());
        memberGraphs = new LockMemberGraphIndex(lockfile.memberGraphs());
    }

    LockDependencyIndex dependencies() {
        return dependencies;
    }

    LockMemberGraphIndex memberGraphs() {
        return memberGraphs;
    }

    List<LockMemberGraph> visibleGraphs(
            List<LockMemberGraph> graphs,
            Set<String> visibleMembers,
            List<LockPackage> packages) {
        Set<PackageKey> packageKeys = new LinkedHashSet<>();
        packages.forEach(lockPackage -> packageKeys.add(PackageKey.of(lockPackage)));
        return graphs.stream()
                .filter(graph -> visibleMembers.contains(graph.member()))
                .filter(graph -> packageKeys.contains(PackageKey.of(graph)))
                .toList();
    }

    private record PackageKey(
            PackageId packageId,
            String version,
            LockArtifactVariant variant,
            DependencyScope scope) {
        static PackageKey of(LockPackage lockPackage) {
            return new PackageKey(
                    lockPackage.packageId(),
                    lockPackage.version(),
                    LockArtifactVariant.of(lockPackage),
                    lockPackage.scope());
        }

        static PackageKey of(LockMemberGraph graph) {
            return new PackageKey(
                    graph.packageId(),
                    graph.version(),
                    graph.variant(),
                    graph.scope());
        }
    }
}
