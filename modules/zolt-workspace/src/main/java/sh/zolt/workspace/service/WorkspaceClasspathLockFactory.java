package sh.zolt.workspace.service;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class WorkspaceClasspathLockFactory {
    ZoltLockfile compileLock(
            WorkspaceExecutionContext context,
            String memberPath) {
        ZoltLockfile lockfile = context.lockfile();
        Set<String> compileMembers = context.memberGraph().mainCompile(memberPath);
        return new ZoltLockfile(
                lockfile.version(),
                compileClasspathPackagesFor(
                        lockfile,
                        memberPath,
                        compileMembers,
                        context.lockIndex()),
                List.of());
    }

    ZoltLockfile runtimeLock(
            WorkspaceExecutionContext context,
            String memberPath) {
        ZoltLockfile lockfile = context.lockfile();
        Set<String> runtimeMembers = context.memberGraph().mainRuntime(memberPath);
        return new ZoltLockfile(
                lockfile.version(),
                runtimeClasspathPackagesFor(
                        lockfile,
                        memberPath,
                        runtimeMembers,
                        visibleMembers(memberPath, runtimeMembers),
                        context.lockIndex()),
                List.of());
    }

    ZoltLockfile testLock(
            WorkspaceExecutionContext context,
            String memberPath) {
        ZoltLockfile lockfile = context.lockfile();
        Set<String> testMembers = context.memberGraph().test(memberPath);
        return new ZoltLockfile(
                lockfile.version(),
                runtimeClasspathPackagesFor(
                        lockfile,
                        memberPath,
                        testMembers,
                        visibleMembers(memberPath, testMembers),
                        context.lockIndex()),
                List.of());
    }

    ZoltLockfile packageLock(
            WorkspaceExecutionContext context,
            String memberPath) {
        ZoltLockfile lockfile = context.lockfile();
        Set<String> runtimeMembers = context.memberGraph().mainRuntime(memberPath);
        Set<String> visibleMembers = visibleMembers(memberPath, runtimeMembers);
        List<LockPackage> packages = runtimeClasspathPackagesFor(
                lockfile,
                memberPath,
                runtimeMembers,
                visibleMembers,
                context.lockIndex());
        List<LockMemberGraph> graphs = context.lockIndex().visibleGraphs(
                lockfile.memberGraphs(),
                visibleMembers,
                packages);
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
            Set<String> dependencyClosure,
            WorkspaceLockIndex lockIndex) {
        Set<String> exportedClosure =
                WorkspaceExportedCompileClosure.compute(
                        lockfile,
                        dependencyClosure,
                        lockIndex);
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
            Set<String> visibleMembers,
            WorkspaceLockIndex lockIndex) {
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
                    || (hasNonOptionalContributor(
                                    lockPackage,
                                    visibleMembers,
                                    lockIndex)
                            && contributesAcrossWorkspaceBoundary(lockPackage.scope()))) {
                filteredPackages.add(WorkspaceMemberPackageLockView.forVisibleMembers(
                        lockPackage,
                        visibleMembers,
                        lockIndex.memberGraphs()));
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
            WorkspaceLockIndex lockIndex) {
        for (String member : lockPackage.members()) {
            if (visibleMembers.contains(member)
                    && !lockIndex.memberGraphs().optionalOnlyFor(member, lockPackage)) {
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
