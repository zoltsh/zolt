package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.lockfile.LockConflict;
import sh.zolt.resolve.DependencyPolicyEffect;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.version.VersionSelectionResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Merges conflict and policy evidence from independently resolved graphs. */
final class LockfileResolutionEvidence {
    private LockfileResolutionEvidence() {
    }

    static List<LockConflict> conflicts(
            VersionSelectionResult mainSelection,
            List<CompilerToolResolution> compilerTools,
            List<ExecToolResolution> execTools) {
        List<LockConflict> conflicts = new ArrayList<>(conflicts(mainSelection, Optional.empty()));
        CompilerToolResolution.ordered(compilerTools).forEach(tool ->
                conflicts.addAll(conflicts(tool.selection(), Optional.empty())));
        execTools.stream()
                .sorted(Comparator.comparing(ExecToolResolution::toolName))
                .forEach(tool -> conflicts.addAll(conflicts(
                        tool.selection(), Optional.of(tool.toolName()))));
        return List.copyOf(conflicts);
    }

    static List<DependencyPolicyEffect> policyEffects(
            ResolutionGraph mainGraph,
            List<CompilerToolResolution> compilerTools,
            List<ExecToolResolution> execTools) {
        List<DependencyPolicyEffect> effects = new ArrayList<>(mainGraph.policyEffects());
        CompilerToolResolution.ordered(compilerTools).forEach(tool ->
                effects.addAll(tool.graph().policyEffects()));
        for (ExecToolResolution tool : execTools) {
            effects.addAll(tool.graph().policyEffects());
        }
        return List.copyOf(effects);
    }

    private static List<LockConflict> conflicts(
            VersionSelectionResult selection,
            Optional<String> toolGroup) {
        return selection.conflicts().stream()
                .map(conflict -> new LockConflict(
                        conflict.packageId(),
                        conflict.selectedVersion(),
                        conflict.requests().stream()
                                .map(DependencyRequest::requestedVersion)
                                .toList(),
                        conflict.selectionReason(),
                        toolGroup,
                        conflict.variant().isDefault()
                                ? Optional.empty()
                                : Optional.of(conflict.variant())))
                .toList();
    }
}
