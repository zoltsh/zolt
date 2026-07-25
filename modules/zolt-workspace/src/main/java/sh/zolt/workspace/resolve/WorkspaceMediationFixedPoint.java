package sh.zolt.workspace.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.ResolveOutput;
import sh.zolt.resolve.ResolveService;
import sh.zolt.resolve.ResolutionVariant;
import sh.zolt.resolve.metrics.ResolveMetrics;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorkspaceMediationFixedPoint {
    private static final int MAX_PASSES = 100;
    private final ResolveService resolveService;

    WorkspaceMediationFixedPoint(ResolveService resolveService) {
        this.resolveService = resolveService;
    }

    WorkspaceMediationResult mediate(
            Workspace workspace,
            List<WorkspaceMemberResolveOutput> initialOutputs,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, ProjectConfig> effectiveConfigs,
            Path cacheRoot,
            ResolveOptions options) {
        WorkspaceExternalPackageSelector selector =
                new WorkspaceExternalPackageSelector();
        List<WorkspaceMemberResolveOutput> outputs =
                List.copyOf(initialOutputs);
        WorkspaceProvidedArtifactMediator provided =
                new WorkspaceProvidedArtifactMediator(workspace);
        Map<String, Integer> seenStates = new LinkedHashMap<>();
        Map<ResolutionVariant, LockConflict> preservedConflicts = new LinkedHashMap<>();
        Set<LockPolicyEffect> preservedPolicyEffects = new LinkedHashSet<>();
        int downloadCount = 0;
        ResolveMetrics metrics = ResolveMetrics.empty();
        for (int pass = 1; pass <= MAX_PASSES; pass++) {
            List<LockPackage> candidates =
                    WorkspaceMediationCandidates.from(workspace, outputs);
            Map<ResolutionVariant, String> overrides =
                    selector.versionOverrides(candidates);
            List<LockConflict> conflicts =
                    selector.versionConflicts(candidates);
            preserveConflicts(preservedConflicts, conflicts);
            Map<ResolutionVariant, String> frontier =
                    WorkspaceMediationFrontier.overrides(outputs, overrides, provided);
            List<LockPolicyEffect> policyEffects =
                    WorkspaceMediationPolicyEffects.from(candidates, frontier);
            preservedPolicyEffects.addAll(policyEffects);
            WorkspaceMediationPolicyEnforcer.enforce(
                    candidates,
                    List.of(),
                    frontier,
                    effectiveConfigs,
                    options.retryCommand());
            if (!requiresOverrides(outputs, overrides, provided)) {
                List<LockConflict> auditConflicts =
                        materializedConflicts(preservedConflicts, conflicts, overrides);
                WorkspaceMediationPolicyEnforcer.enforce(
                        candidates,
                        auditConflicts,
                        overrides,
                        effectiveConfigs,
                        options.retryCommand());
                return new WorkspaceMediationResult(
                        outputs,
                        auditConflicts,
                        List.copyOf(preservedPolicyEffects),
                        downloadCount,
                        metrics);
            }
            requireNewState(
                    outputs, overrides, provided, seenStates, pass, options);
            List<WorkspaceMemberResolveOutput> remediated =
                    new ArrayList<>();
            for (WorkspaceMemberResolveOutput memberOutput : outputs) {
                if (!requiresOverrides(memberOutput, frontier, provided)) {
                    remediated.add(memberOutput);
                    continue;
                }
                WorkspaceMember member =
                        membersByPath.get(memberOutput.member());
                ProjectConfig config =
                        effectiveConfigs.get(member.path());
                ResolveOutput output = resolveService.resolveLockfile(
                        config,
                        cacheRoot,
                        options.withVersionOverrides(
                                applicableOverrides(memberOutput, frontier, provided)));
                remediated.add(
                        WorkspaceMemberResolveOutputFacts.of(
                                member.path(),
                                config,
                                output));
                downloadCount += output.downloadCount();
                metrics = metrics.plus(output.metrics());
            }
            outputs = List.copyOf(remediated);
        }
        throw ResolveException.actionable(
                "Workspace dependency mediation did not stabilize after "
                        + MAX_PASSES
                        + " passes.",
                "Align the changing versions with workspace-wide direct dependencies, [platforms] "
                        + "BOMs, or [dependencyConstraints] strict constraints, then run `"
                        + options.retryCommand()
                        + "` again.");
    }

    private static void requireNewState(
            List<WorkspaceMemberResolveOutput> outputs,
            Map<ResolutionVariant, String> overrides,
            WorkspaceProvidedArtifactMediator provided,
            Map<String, Integer> seenStates,
            int pass,
            ResolveOptions options) {
        String state = state(outputs, overrides);
        Integer previousPass = seenStates.putIfAbsent(state, pass);
        if (previousPass == null) {
            return;
        }
        throw ResolveException.actionable(
                "Workspace dependency mediation repeated the same unresolved state in passes "
                        + previousPass
                        + " and "
                        + pass
                        + " with selections "
                        + overrides
                        + " and unresolved packages "
                        + mismatches(outputs, overrides, provided)
                        + ".",
                "Align the cycling versions with workspace-wide direct dependencies, [platforms] "
                        + "BOMs, or [dependencyConstraints] strict constraints, then run `"
                        + options.retryCommand()
                        + "` again.");
    }

    private static String state(
            List<WorkspaceMemberResolveOutput> outputs,
            Map<ResolutionVariant, String> overrides) {
        StringBuilder state = new StringBuilder(overrides.toString());
        outputs.stream()
                .sorted(java.util.Comparator.comparing(
                        WorkspaceMemberResolveOutput::member))
                .forEach(output -> {
                    state.append('|').append(output.member());
                    output.lockfile().packages().forEach(lockPackage ->
                            state.append(';')
                                    .append(lockPackage.packageId())
                                    .append(':')
                                    .append(lockPackage.version())
                                    .append(':')
                                    .append(LockArtifactVariant.of(
                                            lockPackage).key())
                                    .append(':')
                                    .append(lockPackage.scope().lockfileName())
                                    .append("->")
                                    .append(lockPackage.dependencies()));
                });
        return state.toString();
    }

    private static List<String> mismatches(
            List<WorkspaceMemberResolveOutput> outputs,
            Map<ResolutionVariant, String> overrides,
            WorkspaceProvidedArtifactMediator provided) {
        List<String> mismatches = new ArrayList<>();
        for (WorkspaceMemberResolveOutput output : outputs) {
            for (LockPackage lockPackage : output.lockfile().packages()) {
                if (provided.shadows(output.member(), lockPackage)) {
                    continue;
                }
                String selected = overrides.get(new ResolutionVariant(
                        lockPackage.packageId(),
                        LockArtifactVariant.of(lockPackage)));
                if (selected != null
                        && !selected.equals(lockPackage.version())) {
                    mismatches.add(output.member()
                            + ":"
                            + lockPackage.packageId()
                            + ":"
                            + lockPackage.version()
                            + "->"
                            + selected
                            + ":"
                            + lockPackage.scope().lockfileName());
                }
            }
        }
        return List.copyOf(mismatches);
    }

    private static boolean requiresOverrides(
            List<WorkspaceMemberResolveOutput> outputs,
            Map<ResolutionVariant, String> overrides,
            WorkspaceProvidedArtifactMediator provided) {
        return outputs.stream().anyMatch(
                output -> requiresOverrides(output, overrides, provided));
    }

    private static boolean requiresOverrides(
            WorkspaceMemberResolveOutput output,
            Map<ResolutionVariant, String> overrides,
            WorkspaceProvidedArtifactMediator provided) {
        return output.lockfile().packages().stream()
                .filter(lockPackage ->
                        lockPackage.scope() != DependencyScope.TOOL_EXEC)
                .filter(lockPackage -> !provided.shadows(output.member(), lockPackage))
                .anyMatch(lockPackage -> {
                    String selected = overrides.get(new ResolutionVariant(
                            lockPackage.packageId(),
                            LockArtifactVariant.of(lockPackage)));
                    return selected != null
                            && !selected.equals(lockPackage.version());
                });
    }

    private static Map<ResolutionVariant, String> applicableOverrides(
            WorkspaceMemberResolveOutput output,
            Map<ResolutionVariant, String> overrides,
            WorkspaceProvidedArtifactMediator provided) {
        Map<ResolutionVariant, String> applicable = new LinkedHashMap<>();
        for (LockPackage lockPackage : output.lockfile().packages()) {
            if (lockPackage.scope() == DependencyScope.TOOL_EXEC
                    || provided.shadows(output.member(), lockPackage)) {
                continue;
            }
            ResolutionVariant variant = new ResolutionVariant(
                    lockPackage.packageId(), LockArtifactVariant.of(lockPackage));
            String version = overrides.get(variant);
            if (version != null) {
                applicable.put(variant, version);
            }
        }
        return Map.copyOf(applicable);
    }

    private static void preserveConflicts(
            Map<ResolutionVariant, LockConflict> preserved,
            List<LockConflict> conflicts) {
        for (LockConflict conflict : conflicts) {
            ResolutionVariant key = new ResolutionVariant(
                    conflict.packageId(),
                    conflict.variant().orElseGet(LockArtifactVariant::defaultVariant));
            LockConflict previous = preserved.get(key);
            if (previous == null) {
                preserved.put(key, conflict);
                continue;
            }
            Set<String> requested = new LinkedHashSet<>(previous.requestedVersions());
            requested.addAll(conflict.requestedVersions());
            Set<String> members = new LinkedHashSet<>(previous.members());
            members.addAll(conflict.members());
            preserved.put(key, new LockConflict(
                    conflict.packageId(),
                    conflict.selectedVersion(),
                    requested.stream().sorted().toList(),
                    conflict.reason(),
                    conflict.toolGroup(),
                    conflict.variant(),
                    members.stream().sorted().toList()));
        }
    }

    private static List<LockConflict> materializedConflicts(
            Map<ResolutionVariant, LockConflict> preserved,
            List<LockConflict> active,
            Map<ResolutionVariant, String> selectedVersions) {
        Map<ResolutionVariant, LockConflict> activeByVariant = new LinkedHashMap<>();
        active.forEach(conflict -> activeByVariant.put(
                new ResolutionVariant(
                        conflict.packageId(),
                        conflict.variant().orElseGet(LockArtifactVariant::defaultVariant)),
                conflict));
        return preserved.entrySet().stream()
                .map(entry -> {
                    LockConflict conflict = entry.getValue();
                    LockConflict current = activeByVariant.get(entry.getKey());
                    String selected = selectedVersions.getOrDefault(
                            entry.getKey(), conflict.selectedVersion());
                    sh.zolt.dependency.ConflictSelectionReason reason = current == null
                            ? sh.zolt.dependency.ConflictSelectionReason.SELECTED_GRAPH
                            : current.reason();
                    return new LockConflict(
                            conflict.packageId(),
                            selected,
                            conflict.requestedVersions(),
                            reason,
                            conflict.toolGroup(),
                            conflict.variant(),
                            conflict.members());
                })
                .sorted(Comparator.comparing(conflict ->
                        conflict.packageId() + ":" + conflict.variant().map(LockArtifactVariant::key).orElse("")))
                .toList();
    }
}
