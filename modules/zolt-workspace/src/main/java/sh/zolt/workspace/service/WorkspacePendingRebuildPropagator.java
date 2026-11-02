package sh.zolt.workspace.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reaches a fixed point over pending compile and processor rebuilds before stage 1 starts. */
final class WorkspacePendingRebuildPropagator {
    private WorkspacePendingRebuildPropagator() {
    }

    /**
     * Stage 0 observes dependency outputs before any member in this command is rebuilt. A member
     * whose compile-visible dependency is pending therefore has to enter the pipeline even when the
     * dependency ABI currently on disk still matches recorded state. This includes transitively
     * exported API dependencies: stopping at an unchanged intermediate member can otherwise leave a
     * downstream member compiled against an older classpath until the next command.
     *
     * <p>The same rule applies to workspace processors, whose implementation bytes rather than ABI
     * determine their generated output. Compile and processor edges can alternate, so one pass is not
     * sufficient. The finite member set and idempotent reasons make this loop converge.
     * Test-visible dependencies are checked after that fixed point and receive a test-only reason;
     * pending test compilation never seeds a main rebuild.
     */
    static Map<String, WorkspaceDirtyPlan.MemberPlan> propagate(
            WorkspaceExecutionContext context,
            Map<String, WorkspaceDirtyPlan.MemberPlan> plans,
            Map<String, WorkspaceBuildRequirements> requirementsByMember) {
        Map<String, WorkspaceDirtyPlan.MemberPlan> propagated = new LinkedHashMap<>(plans);
        boolean changed;
        do {
            changed = false;
            for (String memberPath : plans.keySet()) {
                WorkspaceDirtyPlan.MemberPlan memberPlan = propagated.get(memberPath);
                WorkspaceDirtyPlan.MemberPlan updated = memberPlan;
                if (rebuildsACompileDependencyOf(context, propagated, memberPath)) {
                    updated = updated.with(WorkspaceDirtyReason.DEPENDENCY_REBUILD_PENDING);
                }
                if (rebuildsAProcessorOf(context, propagated, memberPath)) {
                    updated = updated.with(WorkspaceDirtyReason.PROCESSOR_INPUT_CHANGED);
                }
                if (updated != memberPlan) {
                    propagated.put(memberPath, updated);
                    changed = true;
                }
            }
        } while (changed);
        for (String memberPath : plans.keySet()) {
            if (requiresTestCompile(requirementsByMember, memberPath)
                    && rebuildsATestDependencyOf(context, propagated, memberPath)) {
                propagated.put(
                        memberPath,
                        propagated.get(memberPath).with(
                                WorkspaceDirtyReason.TEST_DEPENDENCY_REBUILD_PENDING));
            }
        }
        return propagated;
    }

    private static boolean requiresTestCompile(
            Map<String, WorkspaceBuildRequirements> requirementsByMember,
            String memberPath) {
        return requirementsByMember
                .getOrDefault(memberPath, WorkspaceBuildRequirements.mainBuild())
                .testCompileClasspath();
    }

    private static boolean rebuildsATestDependencyOf(
            WorkspaceExecutionContext context,
            Map<String, WorkspaceDirtyPlan.MemberPlan> plans,
            String memberPath) {
        for (String dependency : context.memberGraph().test(memberPath)) {
            WorkspaceDirtyPlan.MemberPlan dependencyPlan = plans.get(dependency);
            if (dependencyPlan != null && dependencyPlan.buildRequired()) {
                return true;
            }
        }
        return false;
    }

    private static boolean rebuildsACompileDependencyOf(
            WorkspaceExecutionContext context,
            Map<String, WorkspaceDirtyPlan.MemberPlan> plans,
            String memberPath) {
        for (String dependency : context.memberGraph().mainCompile(memberPath)) {
            WorkspaceDirtyPlan.MemberPlan dependencyPlan = plans.get(dependency);
            if (dependencyPlan != null && dependencyPlan.buildRequired()) {
                return true;
            }
        }
        return false;
    }

    private static boolean rebuildsAProcessorOf(
            WorkspaceExecutionContext context,
            Map<String, WorkspaceDirtyPlan.MemberPlan> plans,
            String memberPath) {
        for (String processor : WorkspaceCanonicalBuildPolicy.processorMembers(
                context.workspace(),
                memberPath,
                context.memberGraph().compileDependenciesByMember())) {
            WorkspaceDirtyPlan.MemberPlan processorPlan = plans.get(processor);
            if (processorPlan != null && processorPlan.buildRequired()) {
                return true;
            }
        }
        return false;
    }
}
