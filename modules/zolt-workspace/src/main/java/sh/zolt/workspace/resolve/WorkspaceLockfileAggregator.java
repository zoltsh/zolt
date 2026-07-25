package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.service.Workspace;
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
        return aggregate(
                workspace,
                memberOutputs,
                preservedWorkspaceConflicts,
                List.of());
    }

    ZoltLockfile aggregate(
            Workspace workspace,
            List<WorkspaceMemberResolveOutput> memberOutputs,
            List<LockConflict> preservedWorkspaceConflicts,
            List<LockPolicyEffect> preservedWorkspacePolicyEffects) {
        if (isTransitionalRootWorkspace(workspace, memberOutputs)) {
            return memberOutputs.getFirst().lockfile();
        }

        Map<String, LockPackage> packages = new LinkedHashMap<>();
        Map<String, LockConflict> conflicts = new LinkedHashMap<>();
        Map<String, LockPolicyEffect> policyEffects = new LinkedHashMap<>();
        WorkspaceProvidedArtifactMediator provided =
                new WorkspaceProvidedArtifactMediator(workspace);
        for (LockPackage lockPackage :
                new WorkspacePackageAssembler()
                        .assemble(workspace, memberOutputs, provided)) {
            String key = packageKey(lockPackage);
            LockPackage existingPackage = packages.get(key);
            packages.put(key, existingPackage == null ? lockPackage : merge(existingPackage, lockPackage));
        }

        List<LockPackage> externalCandidates = new ArrayList<>();
        for (WorkspaceMemberResolveOutput memberOutput : memberOutputs) {
            for (LockPackage lockPackage :
                    WorkspaceShadowGraphPruner.reachableExternalPackages(
                            memberOutput, provided)) {
                if (!lockPackage.workspace().isPresent()) {
                    externalCandidates.add(withMember(
                            lockPackage,
                            memberOutput.member(),
                            memberOutput.exportedPackages()));
                }
            }
            for (LockConflict conflict : memberOutput.lockfile().conflicts()) {
                addConflict(
                        conflicts,
                        withMember(conflict, memberOutput.member()));
            }
            for (LockPolicyEffect policyEffect : memberOutput.lockfile().policyEffects()) {
                policyEffects.putIfAbsent(policyEffectKey(policyEffect), policyEffect);
            }
        }
        for (LockConflict conflict : provided.conflicts(memberOutputs)) {
            addConflict(conflicts, conflict);
        }

        WorkspaceExternalSelection globalSelection =
                new WorkspaceExternalPackageSelector().selectMaterialized(
                        externalCandidates,
                        provided);
        for (LockPackage lockPackage : globalSelection.packages()) {
            String key = packageKey(lockPackage);
            LockPackage existingPackage = packages.get(key);
            packages.put(key, existingPackage == null ? lockPackage : merge(existingPackage, lockPackage));
        }
        requireUnambiguousGraphTargets(packages.values());
        for (LockConflict conflict : preservedWorkspaceConflicts) {
            addConflict(conflicts, conflict);
        }
        for (LockConflict conflict : globalSelection.conflicts()) {
            addConflict(conflicts, conflict);
        }
        for (LockPolicyEffect policyEffect : preservedWorkspacePolicyEffects) {
            policyEffects.put(policyEffectKey(policyEffect), policyEffect);
        }
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                WorkspaceLockfileFingerprints.aliasFingerprint(memberOutputs),
                WorkspaceLockfileFingerprints.projectResolutionFingerprint(memberOutputs),
                WorkspaceLockfileFingerprints.projectResolutionInputFingerprints(memberOutputs),
                List.copyOf(packages.values()),
                List.copyOf(conflicts.values()),
                List.copyOf(policyEffects.values()),
                WorkspaceMemberGraphFacts.complete(
                        globalSelection, memberOutputs));
    }

    private static void requireUnambiguousGraphTargets(
            Iterable<LockPackage> packages) {
        Map<String, LockPackage> targets = new LinkedHashMap<>();
        for (LockPackage lockPackage : packages) {
            String edge = LockDependencyEdge.of(lockPackage).encode();
            String artifactIdentity = lockPackage.packageId()
                    + ":"
                    + lockPackage.version()
                    + ":"
                    + LockArtifactVariant.of(lockPackage).key();
            LockPackage previous = targets.putIfAbsent(artifactIdentity, lockPackage);
            if (previous == null || sameTarget(previous, lockPackage)) {
                continue;
            }
            throw ResolveException.actionable(
                    "Workspace dependency graph target `"
                            + edge
                            + "` is ambiguous between "
                            + targetDescription(previous)
                            + " and "
                            + targetDescription(lockPackage)
                            + ". Scope cannot make distinct local and released bytes safe under the same Maven package, version, and variant identity.",
                    "Make the workspace dependency explicit for every affected consumer or use a distinct local project version, then run `zolt resolve --workspace` again.");
        }
    }

    private static boolean sameTarget(
            LockPackage left,
            LockPackage right) {
        return left.source().equals(right.source())
                && left.workspace().equals(right.workspace())
                && left.workspaceOutput().equals(right.workspaceOutput())
                && left.jar().equals(right.jar())
                && left.jarSha256().equals(right.jarSha256())
                && left.artifact().equals(right.artifact())
                && left.artifactSha256().equals(right.artifactSha256())
                && left.pom().equals(right.pom())
                && left.pomSha256().equals(right.pomSha256());
    }

    private static String targetDescription(LockPackage lockPackage) {
        return lockPackage.workspace()
                .map(member -> "workspace member `" + member + "`")
                .orElseGet(() -> "repository source `" + lockPackage.source() + "`");
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
        return conflict.packageId()
                + ":" + conflict.toolGroup().orElse("")
                + ":" + conflict.variant()
                        .filter(variant -> !variant.isDefault())
                        .map(LockArtifactVariant::key)
                        .orElse("");
    }

    private static void addConflict(
            Map<String, LockConflict> conflicts,
            LockConflict conflict) {
        String key = conflictKey(conflict);
        LockConflict existing = conflicts.get(key);
        if (existing == null) {
            conflicts.put(key, conflict);
            return;
        }
        Set<String> requested = new LinkedHashSet<>(
                existing.requestedVersions());
        requested.addAll(conflict.requestedVersions());
        Set<String> members = new LinkedHashSet<>(existing.members());
        members.addAll(conflict.members());
        conflicts.put(key, new LockConflict(
                conflict.packageId(),
                conflict.selectedVersion(),
                List.copyOf(requested),
                conflict.reason(),
                conflict.toolGroup(),
                conflict.variant(),
                members.stream().sorted().toList()));
    }

    private static LockConflict withMember(
            LockConflict conflict,
            String member) {
        Set<String> members = new LinkedHashSet<>(conflict.members());
        members.add(member);
        return new LockConflict(
                conflict.packageId(),
                conflict.selectedVersion(),
                conflict.requestedVersions(),
                conflict.reason(),
                conflict.toolGroup(),
                conflict.variant(),
                members.stream().sorted().toList());
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

}
