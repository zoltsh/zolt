package sh.zolt.workspace.resolve;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class WorkspaceLockfileAggregator {
    ZoltLockfile aggregate(Workspace workspace, List<WorkspaceMemberResolveOutput> memberOutputs) {
        return aggregate(workspace, memberOutputs, List.of());
    }

    ZoltLockfile aggregate(
            Workspace workspace,
            List<WorkspaceMemberResolveOutput> memberOutputs,
            List<LockConflict> preservedWorkspaceConflicts) {
        if (isTransitionalRootWorkspace(workspace, memberOutputs)) {
            return memberOutputs.getFirst().lockfile();
        }

        Map<String, LockPackage> packages = new LinkedHashMap<>();
        Map<String, LockConflict> conflicts = new LinkedHashMap<>();
        Map<String, LockPolicyEffect> policyEffects = new LinkedHashMap<>();
        Map<WorkspaceCoordinateScope, String> workspaceProvidedVersions = new LinkedHashMap<>();
        for (LockPackage lockPackage : workspacePackages(workspace)) {
            String key = packageKey(lockPackage);
            LockPackage existingPackage = packages.get(key);
            packages.put(key, existingPackage == null ? lockPackage : merge(existingPackage, lockPackage));
            workspaceProvidedVersions.putIfAbsent(
                    new WorkspaceCoordinateScope(
                            lockPackage.packageId(), LockArtifactVariant.of(lockPackage), lockPackage.scope()),
                    lockPackage.version());
        }

        List<LockPackage> externalCandidates = new ArrayList<>();
        Map<WorkspaceCoordinateScope, Set<String>> shadowedExternalVersions = new LinkedHashMap<>();
        for (WorkspaceMemberResolveOutput memberOutput : memberOutputs) {
            for (LockPackage lockPackage : memberOutput.lockfile().packages()) {
                if (!lockPackage.workspace().isPresent()) {
                    WorkspaceCoordinateScope coordinateScope = new WorkspaceCoordinateScope(
                            lockPackage.packageId(), LockArtifactVariant.of(lockPackage), lockPackage.scope());
                    if (workspaceProvidedVersions.containsKey(coordinateScope)) {
                        // A workspace member provides this coordinate at this scope; the reactor
                        // version shadows the external same-coordinate transitive (Maven-consistent).
                        // Drop the external so two live versions never reach a member's classpath, but
                        // record the collision as a conflict rather than resolving it silently.
                        shadowedExternalVersions
                                .computeIfAbsent(coordinateScope, ignored -> new LinkedHashSet<>())
                                .add(lockPackage.version());
                        continue;
                    }
                    externalCandidates.add(withMember(
                            lockPackage,
                            memberOutput.member(),
                            memberOutput.exportedPackages()));
                }
            }
            for (LockConflict conflict : memberOutput.lockfile().conflicts()) {
                conflicts.putIfAbsent(conflictKey(conflict), conflict);
            }
            for (LockPolicyEffect policyEffect : memberOutput.lockfile().policyEffects()) {
                policyEffects.putIfAbsent(policyEffectKey(policyEffect), policyEffect);
            }
        }

        WorkspaceExternalSelection globalSelection = new WorkspaceExternalPackageSelector().select(externalCandidates);
        for (LockPackage lockPackage : globalSelection.packages()) {
            String key = packageKey(lockPackage);
            LockPackage existingPackage = packages.get(key);
            packages.put(key, existingPackage == null ? lockPackage : merge(existingPackage, lockPackage));
        }
        for (LockConflict conflict : globalSelection.conflicts()) {
            conflicts.put(conflictKey(conflict), conflict);
        }
        for (LockConflict conflict : preservedWorkspaceConflicts) {
            conflicts.put(conflictKey(conflict), conflict);
        }
        for (Map.Entry<WorkspaceCoordinateScope, Set<String>> entry : shadowedExternalVersions.entrySet()) {
            WorkspaceCoordinateScope coordinateScope = entry.getKey();
            String workspaceVersion = workspaceProvidedVersions.get(coordinateScope);
            Set<String> requestedVersions = new LinkedHashSet<>();
            requestedVersions.add(workspaceVersion);
            requestedVersions.addAll(entry.getValue());
            if (requestedVersions.size() > 1) {
                LockConflict conflict = new LockConflict(
                        coordinateScope.packageId(),
                        workspaceVersion,
                        List.copyOf(requestedVersions),
                        ConflictSelectionReason.DIRECT_DEPENDENCY,
                        Optional.empty(),
                        Optional.of(coordinateScope.variant()));
                conflicts.put(conflictKey(conflict), conflict);
            }
        }

        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                WorkspaceLockfileFingerprints.aliasFingerprint(memberOutputs),
                WorkspaceLockfileFingerprints.projectResolutionFingerprint(memberOutputs),
                WorkspaceLockfileFingerprints.projectResolutionInputFingerprints(memberOutputs),
                List.copyOf(packages.values()),
                List.copyOf(conflicts.values()),
                List.copyOf(policyEffects.values()));
    }

    private static boolean isTransitionalRootWorkspace(
            Workspace workspace,
            List<WorkspaceMemberResolveOutput> memberOutputs) {
        return workspace.members().size() == 1
                && workspace.edges().isEmpty()
                && workspace.members().getFirst().path().equals(".")
                && memberOutputs.size() == 1
                && memberOutputs.getFirst().member().equals(".");
    }

    private static List<LockPackage> workspacePackages(Workspace workspace) {
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        List<LockPackage> packages = new ArrayList<>();
        for (WorkspaceProjectEdge edge : workspace.edges()) {
            WorkspaceMember target = membersByPath.get(edge.to());
            packages.add(new LockPackage(
                    packageId(edge.coordinate()),
                    target.config().project().version(),
                    "workspace",
                    scope(edge.scope()),
                    true,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(edge.to()),
                    Optional.of(workspaceOutput(workspace.root(), target)),
                    List.of(),
                    List.of(edge.from()),
                    edge.exported() ? List.of(edge.from()) : List.of(),
                    List.of(),
                    List.of()));
        }
        return packages;
    }

    private static String workspaceOutput(Path workspaceRoot, WorkspaceMember member) {
        String configuredOutput = member.config().build().output();
        try {
            Path memberRoot = ProjectPaths.existingRoot(
                    ProjectPaths.root(workspaceRoot),
                    "[workspace].members",
                    member.path());
            ProjectPaths.output(memberRoot, "[build].output", configuredOutput);
            return configuredOutput;
        } catch (ProjectPathException exception) {
            throw new ResolveException(
                    "Workspace member `"
                            + member.path()
                            + "` has an invalid [build].output. "
                            + exception.getMessage(),
                    exception);
        }
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }

    private static DependencyScope scope(String value) {
        return switch (value) {
            case "compile" -> DependencyScope.COMPILE;
            case "test" -> DependencyScope.TEST;
            case "processor" -> DependencyScope.PROCESSOR;
            case "test-processor" -> DependencyScope.TEST_PROCESSOR;
            default -> throw new ResolveException("Unsupported workspace dependency scope `" + value + "`.");
        };
    }

    private static LockPackage merge(LockPackage left, LockPackage right) {
        Set<String> dependencies = new LinkedHashSet<>(left.dependencies());
        dependencies.addAll(right.dependencies());
        Set<String> members = new LinkedHashSet<>(left.members());
        members.addAll(right.members());
        Set<String> exportedBy = new LinkedHashSet<>(left.exportedBy());
        exportedBy.addAll(right.exportedBy());
        return new LockPackage(
                left.packageId(),
                left.version(),
                left.source(),
                left.scope(),
                left.direct() || right.direct(),
                firstPresent(left.jar(), right.jar()),
                firstPresent(left.pom(), right.pom()),
                firstPresent(left.jarSha256(), right.jarSha256()),
                firstPresent(left.pomSha256(), right.pomSha256()),
                firstPresent(left.artifact(), right.artifact()),
                firstPresent(left.artifactType(), right.artifactType()),
                firstPresent(left.artifactSha256(), right.artifactSha256()),
                firstPresent(left.workspace(), right.workspace()),
                firstPresent(left.workspaceOutput(), right.workspaceOutput()),
                List.copyOf(dependencies),
                List.copyOf(members),
                List.copyOf(exportedBy),
                merged(left.policies(), right.policies()),
                mergedToolGroups(left.toolGroups(), right.toolGroups()));
    }

    private static LockPackage withMember(
            LockPackage lockPackage,
            String member,
            Set<WorkspaceExportedPackage> exportedPackages) {
        Set<String> members = new LinkedHashSet<>(lockPackage.members());
        members.add(member);
        Set<String> exportedBy = new LinkedHashSet<>(lockPackage.exportedBy());
        if (exportedPackages.contains(new WorkspaceExportedPackage(
                lockPackage.packageId(), LockArtifactVariant.of(lockPackage)))) {
            exportedBy.add(member);
        }
        return new LockPackage(
                lockPackage.packageId(),
                lockPackage.version(),
                lockPackage.source(),
                lockPackage.scope(),
                lockPackage.direct(),
                lockPackage.jar(),
                lockPackage.pom(),
                lockPackage.jarSha256(),
                lockPackage.pomSha256(),
                lockPackage.artifact(),
                lockPackage.artifactType(),
                lockPackage.artifactSha256(),
                lockPackage.workspace(),
                lockPackage.workspaceOutput(),
                lockPackage.dependencies(),
                List.copyOf(members),
                List.copyOf(exportedBy),
                lockPackage.policies(),
                lockPackage.toolGroups());
    }

    private static List<String> merged(List<String> left, List<String> right) {
        Set<String> values = new LinkedHashSet<>(left);
        values.addAll(right);
        return List.copyOf(values);
    }

    private static List<String> mergedToolGroups(List<String> left, List<String> right) {
        Set<String> values = new LinkedHashSet<>(left);
        values.addAll(right);
        return values.stream().sorted().toList();
    }

    private static Optional<String> firstPresent(Optional<String> left, Optional<String> right) {
        return left.isPresent() ? left : right;
    }

    private static String packageKey(LockPackage lockPackage) {
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + ":"
                + lockPackage.source()
                + ":"
                + lockPackage.scope().lockfileName()
                + ":"
                + LockArtifactVariant.of(lockPackage).key();
    }

    private static String conflictKey(LockConflict conflict) {
        return conflict.packageId() + ":" + conflict.selectedVersion() + ":" + conflict.reason()
                + ":" + conflict.toolGroup().orElse("")
                + ":" + conflict.variant().map(LockArtifactVariant::key).orElse("");
    }

    private static String policyEffectKey(LockPolicyEffect policyEffect) {
        return policyEffect.kind()
                + ":"
                + policyEffect.packageId()
                + ":"
                + policyEffect.requestedVersion().orElse("")
                + ":"
                + policyEffect.source().orElse("")
                + ":"
                + policyEffect.policy();
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }

    /**
     * The identity a workspace member's provided artifact shadows an external at: its {@link PackageId},
     * its artifact {@link LockArtifactVariant}, and scope. A member provides only the plain {@code jar}
     * (default variant), so a classified external attachment of the same GA — a {@code :tests} jar the
     * member does NOT provide — has a distinct variant, misses this key, and survives onto the classpath.
     */
    private record WorkspaceCoordinateScope(PackageId packageId, LockArtifactVariant variant, DependencyScope scope) {
    }
}
