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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, Integer> seenStates = new LinkedHashMap<>();
        int downloadCount = 0;
        ResolveMetrics metrics = ResolveMetrics.empty();
        for (int pass = 1; pass <= MAX_PASSES; pass++) {
            List<LockPackage> candidates =
                    WorkspaceMediationCandidates.from(workspace, outputs);
            Map<ResolutionVariant, String> overrides =
                    selector.versionOverrides(candidates);
            List<LockConflict> conflicts =
                    selector.versionConflicts(candidates);
            WorkspaceMediationPolicyEnforcer.enforce(
                    candidates,
                    conflicts,
                    overrides,
                    effectiveConfigs,
                    options.retryCommand());
            List<LockPolicyEffect> policyEffects =
                    WorkspaceMediationPolicyEffects.from(
                            candidates, overrides);
            if (!requiresOverrides(outputs, overrides)) {
                return new WorkspaceMediationResult(
                        outputs,
                        conflicts,
                        policyEffects,
                        downloadCount,
                        metrics);
            }
            requireNewState(
                    outputs, overrides, seenStates, pass, options);
            List<WorkspaceMemberResolveOutput> remediated =
                    new ArrayList<>();
            for (WorkspaceMemberResolveOutput memberOutput : outputs) {
                if (!requiresOverrides(memberOutput, overrides)) {
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
                        options.withVersionOverrides(overrides));
                remediated.add(
                        WorkspaceMemberResolveOutputFacts.of(
                                member.path(),
                                config,
                                output.lockfile()));
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
                        + mismatches(outputs, overrides)
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
            Map<ResolutionVariant, String> overrides) {
        List<String> mismatches = new ArrayList<>();
        for (WorkspaceMemberResolveOutput output : outputs) {
            for (LockPackage lockPackage : output.lockfile().packages()) {
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
            Map<ResolutionVariant, String> overrides) {
        return outputs.stream().anyMatch(
                output -> requiresOverrides(output, overrides));
    }

    private static boolean requiresOverrides(
            WorkspaceMemberResolveOutput output,
            Map<ResolutionVariant, String> overrides) {
        return output.lockfile().packages().stream()
                .filter(lockPackage ->
                        lockPackage.scope() != DependencyScope.TOOL_EXEC)
                .anyMatch(lockPackage -> {
                    String selected = overrides.get(new ResolutionVariant(
                            lockPackage.packageId(),
                            LockArtifactVariant.of(lockPackage)));
                    return selected != null
                            && !selected.equals(lockPackage.version());
                });
    }
}
