package sh.zolt.workspace.publish;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BomSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a BOM member's family and builds a single-project-shaped lockfile whose packages are the
 * family members as {@code source="workspace"} entries at their locked versions. Fed to
 * {@code PublishPomGenerator}, these become the {@code <dependencyManagement>} member entries.
 *
 * <p>Per the contract, {@code members = true} resolves from the enclosing workspace's member graph
 * (every sibling member, minus the BOM itself, other BOMs, and any excluded paths); an explicit path
 * list selects exactly those members. Member versions come from the aggregated lock's workspace
 * packages when present, falling back to the member's own declared version (they are identical — the
 * aggregator stamps each workspace package with the provider member's version).
 */
public final class WorkspaceBomFamily {
    public ZoltLockfile familyLock(Workspace workspace, ZoltLockfile aggregatedLock, WorkspaceMember bomMember) {
        Map<String, String> versionByCoordinate = new LinkedHashMap<>();
        for (LockPackage lockPackage : aggregatedLock.packages()) {
            if (lockPackage.workspace().isPresent()) {
                versionByCoordinate.putIfAbsent(
                        lockPackage.packageId().groupId() + ":" + lockPackage.packageId().artifactId(),
                        lockPackage.version());
            }
        }

        List<LockPackage> packages = new ArrayList<>();
        for (WorkspaceMember member : familyMembers(workspace, bomMember)) {
            String coordinate = member.config().project().group() + ":" + member.config().project().name();
            String version = versionByCoordinate.getOrDefault(coordinate, member.config().project().version());
            packages.add(new LockPackage(
                    new PackageId(member.config().project().group(), member.config().project().name()),
                    version,
                    "workspace",
                    DependencyScope.COMPILE,
                    true,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(member.path()),
                    Optional.empty(),
                    List.of()));
        }
        return new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.copyOf(packages), List.of());
    }

    private static List<WorkspaceMember> familyMembers(Workspace workspace, WorkspaceMember bomMember) {
        BomSettings.Members members = bomMember.config().packageSettings().bom().members();
        List<WorkspaceMember> family = new ArrayList<>();
        if (members.all()) {
            for (WorkspaceMember member : workspace.members()) {
                if (member.path().equals(bomMember.path())
                        || member.config().packageSettings().mode() == PackageMode.BOM
                        || members.exclude().contains(member.path())) {
                    continue;
                }
                family.add(member);
            }
            return family;
        }
        Map<String, WorkspaceMember> byPath = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            byPath.put(member.path(), member);
        }
        for (String path : members.paths()) {
            if (members.exclude().contains(path)) {
                continue;
            }
            WorkspaceMember member = byPath.get(path);
            if (member != null && !member.path().equals(bomMember.path())) {
                family.add(member);
            }
        }
        return family;
    }
}
