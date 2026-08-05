package sh.zolt.workspace.service;

import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.resolve.WorkspaceMemberLaneClosure;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Projects one member's lane out of the root lock.
 *
 * <p>Which packages are on the lane is not decided here: {@link WorkspaceMemberLaneClosure} owns that
 * rule so stage 0's dirtiness keys and these projections cannot disagree about what moves a lane.
 * This class turns the closure's answer back into a lock view, in the root lock's own order, and
 * applies the per-lane narrowing the view needs.
 */
final class WorkspaceClasspathLockFactory {
    ZoltLockfile compileLock(
            WorkspaceExecutionContext context,
            String memberPath) {
        ZoltLockfile lockfile = context.lockfile();
        return new ZoltLockfile(
                lockfile.version(),
                lanePackages(lockfile, context.laneClosure().mainCompile(memberPath)),
                List.of());
    }

    ZoltLockfile runtimeLock(
            WorkspaceExecutionContext context,
            String memberPath) {
        ZoltLockfile lockfile = context.lockfile();
        return new ZoltLockfile(
                lockfile.version(),
                visibleLanePackages(
                        context,
                        context.laneClosure().mainRuntime(memberPath),
                        visibleMembers(memberPath, context.memberGraph().mainRuntime(memberPath))),
                List.of());
    }

    ZoltLockfile testLock(
            WorkspaceExecutionContext context,
            String memberPath) {
        ZoltLockfile lockfile = context.lockfile();
        return new ZoltLockfile(
                lockfile.version(),
                visibleLanePackages(
                        context,
                        context.laneClosure().test(memberPath),
                        visibleMembers(memberPath, context.memberGraph().test(memberPath))),
                List.of());
    }

    ZoltLockfile packageLock(
            WorkspaceExecutionContext context,
            String memberPath) {
        ZoltLockfile lockfile = context.lockfile();
        Set<String> visibleMembers =
                visibleMembers(memberPath, context.memberGraph().mainRuntime(memberPath));
        List<LockPackage> packages = visibleLanePackages(
                context,
                context.laneClosure().mainRuntime(memberPath),
                visibleMembers);
        List<LockMemberGraph> graphs = context.lockIndex().visibleGraphs(
                lockfile.memberGraphs(),
                visibleMembers,
                packages);
        return new ZoltLockfile(
                lockfile.version(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                packages,
                List.of(),
                List.of(),
                graphs);
    }

    private static List<LockPackage> lanePackages(
            ZoltLockfile lockfile,
            WorkspaceMemberLaneClosure.Lane lane) {
        List<LockPackage> packages = lockfile.packages();
        List<LockPackage> filteredPackages = new ArrayList<>();
        for (int index = 0; index < packages.size(); index++) {
            if (lane.contains(index)) {
                filteredPackages.add(packages.get(index));
            }
        }
        return filteredPackages;
    }

    /**
     * The runtime-family lanes narrow aggregate policy effects to the members that can see the
     * package. A workspace record names exactly one member and carries no aggregate effects, so it
     * rides through untouched.
     */
    private static List<LockPackage> visibleLanePackages(
            WorkspaceExecutionContext context,
            WorkspaceMemberLaneClosure.Lane lane,
            Set<String> visibleMembers) {
        List<LockPackage> packages = context.lockfile().packages();
        List<LockPackage> filteredPackages = new ArrayList<>();
        for (int index = 0; index < packages.size(); index++) {
            if (!lane.contains(index)) {
                continue;
            }
            LockPackage lockPackage = packages.get(index);
            filteredPackages.add(lockPackage.workspace().isPresent()
                    ? lockPackage
                    : WorkspaceMemberPackageLockView.forVisibleMembers(
                            lockPackage,
                            visibleMembers,
                            context.lockIndex().memberGraphs()));
        }
        return filteredPackages;
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
