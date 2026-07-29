package sh.zolt.workspace.service;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class WorkspaceClasspathLockFactory {
    ZoltLockfile compileLock(
            ZoltLockfile lockfile,
            String memberPath,
            WorkspaceClasspathMemberGraph memberGraph) {
        Set<String> compileMembers = memberGraph.mainCompile(memberPath);
        return new ZoltLockfile(
                lockfile.version(),
                compileClasspathPackagesFor(lockfile, memberPath, compileMembers),
                List.of());
    }

    ZoltLockfile runtimeLock(
            ZoltLockfile lockfile,
            String memberPath,
            WorkspaceClasspathMemberGraph memberGraph) {
        Set<String> runtimeMembers = memberGraph.mainRuntime(memberPath);
        return new ZoltLockfile(
                lockfile.version(),
                runtimeClasspathPackagesFor(
                        lockfile,
                        memberPath,
                        runtimeMembers,
                        visibleMembers(memberPath, runtimeMembers)),
                List.of());
    }

    ZoltLockfile testLock(
            ZoltLockfile lockfile,
            String memberPath,
            WorkspaceClasspathMemberGraph memberGraph) {
        Set<String> testMembers = memberGraph.test(memberPath);
        return new ZoltLockfile(
                lockfile.version(),
                runtimeClasspathPackagesFor(
                        lockfile,
                        memberPath,
                        testMembers,
                        visibleMembers(memberPath, testMembers)),
                List.of());
    }

    ZoltLockfile packageLock(
            ZoltLockfile lockfile,
            String memberPath,
            WorkspaceClasspathMemberGraph memberGraph) {
        Set<String> runtimeMembers = memberGraph.mainRuntime(memberPath);
        Set<String> visibleMembers = visibleMembers(memberPath, runtimeMembers);
        List<LockPackage> packages = runtimeClasspathPackagesFor(
                lockfile,
                memberPath,
                runtimeMembers,
                visibleMembers);
        List<LockMemberGraph> graphs = lockfile.memberGraphs().stream()
                .filter(graph -> visibleMembers.contains(graph.member()))
                .filter(graph -> packages.stream().anyMatch(graph::describes))
                .toList();
        return new ZoltLockfile(
                lockfile.version(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                List.of(),
                packages,
                List.of(),
                List.of(),
                graphs);
    }

    private static List<LockPackage> compileClasspathPackagesFor(
            ZoltLockfile lockfile,
            String memberPath,
            Set<String> dependencyClosure) {
        Set<String> exportedClosure =
                WorkspaceExportedCompileClosure.compute(lockfile, dependencyClosure);
        List<LockPackage> filteredPackages = new ArrayList<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            if (lockPackage.workspace().isPresent()) {
                if (dependencyClosure.contains(lockPackage.workspace().orElseThrow())) {
                    filteredPackages.add(lockPackage);
                }
                continue;
            }
            if (lockPackage.members().isEmpty()
                    || lockPackage.members().contains(memberPath)
                    || exportedClosure.contains(WorkspaceExportedCompileClosure.ref(lockPackage))) {
                filteredPackages.add(lockPackage);
            }
        }
        return filteredPackages;
    }

    private static List<LockPackage> runtimeClasspathPackagesFor(
            ZoltLockfile lockfile,
            String memberPath,
            Set<String> dependencyClosure,
            Set<String> visibleMembers) {
        LockMemberGraphIndex memberGraphs = new LockMemberGraphIndex(lockfile.memberGraphs());
        List<LockPackage> filteredPackages = new ArrayList<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            if (lockPackage.workspace().isPresent()) {
                if (dependencyClosure.contains(lockPackage.workspace().orElseThrow())) {
                    filteredPackages.add(lockPackage);
                }
                continue;
            }
            if (lockPackage.members().isEmpty()
                    || lockPackage.members().contains(memberPath)
                    || (hasNonOptionalContributor(lockPackage, visibleMembers, memberGraphs)
                            && contributesAcrossWorkspaceBoundary(lockPackage.scope()))) {
                filteredPackages.add(WorkspaceMemberPackageLockView.forVisibleMembers(
                        lockPackage,
                        visibleMembers,
                        memberGraphs));
            }
        }
        return filteredPackages;
    }

    private static boolean contributesAcrossWorkspaceBoundary(DependencyScope scope) {
        return scope == DependencyScope.COMPILE || scope == DependencyScope.RUNTIME;
    }

    private static boolean hasNonOptionalContributor(
            LockPackage lockPackage,
            Set<String> visibleMembers,
            LockMemberGraphIndex memberGraphs) {
        for (String member : lockPackage.members()) {
            if (visibleMembers.contains(member)
                    && !memberGraphs.optionalOnlyFor(member, lockPackage)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> visibleMembers(
            String memberPath,
            Set<String> dependencyMembers) {
        Set<String> visible = new LinkedHashSet<>();
        visible.add(memberPath);
        visible.addAll(dependencyMembers);
        return visible;
    }
}
